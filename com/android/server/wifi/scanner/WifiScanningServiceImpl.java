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

package com.android.server.wifi.scanner;

import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.Manifest;
import android.app.AlarmManager;
import android.content.Context;
import android.net.wifi.IWifiScanner;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiScanner.ChannelSpec;
import android.net.wifi.WifiScanner.PnoSettings;
import android.net.wifi.WifiScanner.ScanData;
import android.net.wifi.WifiScanner.ScanSettings;
import android.os.Binder;
import android.os.Bundle;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.WorkSource;
import android.util.ArrayMap;
import android.util.LocalLog;
import android.util.Log;
import android.util.Pair;
import android.util.StatsLog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IBatteryStats;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.ClientModeImpl;
import com.android.server.wifi.Clock;
import com.android.server.wifi.FrameworkFacade;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.WifiLog;
import com.android.server.wifi.WifiMetrics;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.nano.WifiMetricsProto;
import com.android.server.wifi.scanner.ChannelHelper.ChannelCollection;
import com.android.server.wifi.util.ScanResultUtil;
import com.android.server.wifi.util.WifiHandler;
import com.android.server.wifi.util.WifiPermissionsUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class WifiScanningServiceImpl extends IWifiScanner.Stub {

    private static final String TAG = WifiScanningService.TAG;
    private static final boolean DBG = false;

    private static final int UNKNOWN_PID = -1;

    private final LocalLog mLocalLog = new LocalLog(512);

    private WifiLog mLog;

    private void localLog(String message) {
        mLocalLog.log(message);
    }

    private void logw(String message) {
        Log.w(TAG, message);
        mLocalLog.log(message);
    }

    private void loge(String message) {
        Log.e(TAG, message);
        mLocalLog.log(message);
    }

    private WifiScannerImpl mScannerImpl;

    @Override
    public Messenger getMessenger() {
        if (mClientHandler != null) {
            mLog.trace("getMessenger() uid=%").c(Binder.getCallingUid()).flush();
            return new Messenger(mClientHandler);
        }
        loge("WifiScanningServiceImpl trying to get messenger w/o initialization");
        return null;
    }

    @Override
    public Bundle getAvailableChannels(int band) {
        mChannelHelper.updateChannels();
        ChannelSpec[] channelSpecs = mChannelHelper.getAvailableScanChannels(band);
        ArrayList<Integer> list = new ArrayList<Integer>(channelSpecs.length);
        for (ChannelSpec channelSpec : channelSpecs) {
            list.add(channelSpec.frequency);
        }
        Bundle b = new Bundle();
        b.putIntegerArrayList(WifiScanner.GET_AVAILABLE_CHANNELS_EXTRA, list);
        mLog.trace("getAvailableChannels uid=%").c(Binder.getCallingUid()).flush();
        return b;
    }

    private void enforceNetworkStack(int uid) {
        mContext.enforcePermission(
                Manifest.permission.NETWORK_STACK,
                UNKNOWN_PID, uid,
                "NetworkStack");
    }

    // Helper method to check if the incoming message is for a privileged request.
    private boolean isPrivilegedMessage(int msgWhat) {
        return (msgWhat == WifiScanner.CMD_ENABLE
                || msgWhat == WifiScanner.CMD_DISABLE
                || msgWhat == WifiScanner.CMD_START_PNO_SCAN
                || msgWhat == WifiScanner.CMD_STOP_PNO_SCAN
                || msgWhat == WifiScanner.CMD_REGISTER_SCAN_LISTENER);
    }

    // For non-privileged requests, retrieve the bundled package name for app-op & permission
    // checks.
    private String getPackageName(Message msg) {
        if (!(msg.obj instanceof Bundle)) {
            return null;
        }
        Bundle bundle = (Bundle) msg.obj;
        return bundle.getString(WifiScanner.REQUEST_PACKAGE_NAME_KEY);
    }

    // Check if we should ignore location settings if this is a single scan request.
    private boolean shouldIgnoreLocationSettingsForSingleScan(Message msg) {
        if (msg.what != WifiScanner.CMD_START_SINGLE_SCAN) return false;
        if (!(msg.obj instanceof Bundle)) return false;
        Bundle bundle = (Bundle) msg.obj;
        ScanSettings scanSettings = bundle.getParcelable(WifiScanner.SCAN_PARAMS_SCAN_SETTINGS_KEY);
        return scanSettings.ignoreLocationSettings;
    }

    // Check if we should hide this request from app-ops if this is a single scan request.
    private boolean shouldHideFromAppsForSingleScan(Message msg) {
        if (msg.what != WifiScanner.CMD_START_SINGLE_SCAN) return false;
        if (!(msg.obj instanceof Bundle)) return false;
        Bundle bundle = (Bundle) msg.obj;
        ScanSettings scanSettings = bundle.getParcelable(WifiScanner.SCAN_PARAMS_SCAN_SETTINGS_KEY);
        return scanSettings.hideFromAppOps;
    }

    /**
     * Enforce the necessary client permissions for WifiScanner.
     * If the client has NETWORK_STACK permission, then it can "always" send "any" request.
     * If the client has only LOCATION_HARDWARE permission, then it can
     *    a) Only make scan related requests when location is turned on.
     *    b) Can never make one of the privileged requests.
     *
     * @param uid Uid of the client.
     * @param msg {@link Message} of the incoming request.
     * @throws {@link SecurityException} if the client does not have the necessary permissions.
     */
    private void enforcePermission(int uid, Message msg) throws SecurityException {
        try {
            /** Wifi stack issued requests.*/
            enforceNetworkStack(uid);
        } catch (SecurityException e) {
            /** System-app issued requests. */
            if (isPrivilegedMessage(msg.what)) {
                // Privileged message, only requests from clients with NETWORK_STACK allowed!
                throw e;
            }
            mWifiPermissionsUtil.enforceCanAccessScanResultsForWifiScanner(
                    getPackageName(msg), uid, shouldIgnoreLocationSettingsForSingleScan(msg),
                    shouldHideFromAppsForSingleScan(msg));
        }
    }

    private class ClientHandler extends WifiHandler {

        ClientHandler(String tag, Looper looper) {
            super(tag, looper);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                    if (msg.replyTo == null) {
                        logw("msg.replyTo is null");
                        return;
                    }
                    ExternalClientInfo client = (ExternalClientInfo) mClients.get(msg.replyTo);
                    if (client != null) {
                        logw("duplicate client connection: " + msg.sendingUid + ", messenger="
                                + msg.replyTo);
                        client.mChannel.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_FULL_CONNECTION_REFUSED_ALREADY_CONNECTED);
                        return;
                    }

                    AsyncChannel ac = mFrameworkFacade.makeWifiAsyncChannel(TAG);
                    ac.connected(mContext, this, msg.replyTo);

                    client = new ExternalClientInfo(msg.sendingUid, msg.replyTo, ac);
                    client.register();

                    ac.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                            AsyncChannel.STATUS_SUCCESSFUL);
                    localLog("client connected: " + client);
                    return;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECT: {
                    ExternalClientInfo client = (ExternalClientInfo) mClients.get(msg.replyTo);
                    if (client != null) {
                        client.mChannel.disconnect();
                    }
                    return;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    ExternalClientInfo client = (ExternalClientInfo) mClients.get(msg.replyTo);
                    if (client != null && msg.arg1 != AsyncChannel.STATUS_SEND_UNSUCCESSFUL
                            && msg.arg1
                            != AsyncChannel.STATUS_FULL_CONNECTION_REFUSED_ALREADY_CONNECTED) {
                        localLog("client disconnected: " + client + ", reason: " + msg.arg1);
                        client.cleanup();
                    }
                    return;
                }
            }

            try {
                enforcePermission(msg.sendingUid, msg);
            } catch (SecurityException e) {
                localLog("failed to authorize app: " + e);
                replyFailed(msg, WifiScanner.REASON_NOT_AUTHORIZED, "Not authorized");
                return;
            }

            // Since the CMD_GET_SCAN_RESULTS and CMD_GET_SINGLE_SCAN_RESULTS messages are
            // sent from WifiScanner using |sendMessageSynchronously|, handle separately since
            // the |msg.replyTo| field does not actually correspond to the Messenger that is
            // registered for that client.
            if (msg.what == WifiScanner.CMD_GET_SCAN_RESULTS) {
                mBackgroundScanStateMachine.sendMessage(Message.obtain(msg));
                return;
            }
            if (msg.what == WifiScanner.CMD_GET_SINGLE_SCAN_RESULTS) {
                mSingleScanStateMachine.sendMessage(Message.obtain(msg));
                return;
            }

            ClientInfo ci = mClients.get(msg.replyTo);
            if (ci == null) {
                loge("Could not find client info for message " + msg.replyTo + ", msg=" + msg);
                replyFailed(msg, WifiScanner.REASON_INVALID_LISTENER, "Could not find listener");
                return;
            }

            switch (msg.what) {
                case WifiScanner.CMD_ENABLE:
                    mBackgroundScanStateMachine.sendMessage(CMD_DRIVER_LOADED);
                    mSingleScanStateMachine.sendMessage(CMD_DRIVER_LOADED);
                    mPnoScanStateMachine.sendMessage(CMD_DRIVER_LOADED);
                    break;
                case WifiScanner.CMD_DISABLE:
                    mBackgroundScanStateMachine.sendMessage(CMD_DRIVER_UNLOADED);
                    mSingleScanStateMachine.sendMessage(CMD_DRIVER_UNLOADED);
                    mPnoScanStateMachine.sendMessage(CMD_DRIVER_UNLOADED);
                    break;
                case WifiScanner.CMD_START_BACKGROUND_SCAN:
                case WifiScanner.CMD_STOP_BACKGROUND_SCAN:
                    mBackgroundScanStateMachine.sendMessage(Message.obtain(msg));
                    break;
                case WifiScanner.CMD_START_PNO_SCAN:
                case WifiScanner.CMD_STOP_PNO_SCAN:
                    mPnoScanStateMachine.sendMessage(Message.obtain(msg));
                    break;
                case WifiScanner.CMD_START_SINGLE_SCAN:
                case WifiScanner.CMD_STOP_SINGLE_SCAN:
                    mSingleScanStateMachine.sendMessage(Message.obtain(msg));
                    break;
                case WifiScanner.CMD_REGISTER_SCAN_LISTENER:
                    logScanRequest("registerScanListener", ci, msg.arg2, null, null, null);
                    mSingleScanListeners.addRequest(ci, msg.arg2, null, null);
                    replySucceeded(msg);
                    break;
                case WifiScanner.CMD_DEREGISTER_SCAN_LISTENER:
                    logScanRequest("deregisterScanListener", ci, msg.arg2, null, null, null);
                    mSingleScanListeners.removeRequest(ci, msg.arg2);
                    break;
                default:
                    replyFailed(msg, WifiScanner.REASON_INVALID_REQUEST, "Invalid request");
                    break;
            }
        }
    }

    private static final int BASE = Protocol.BASE_WIFI_SCANNER_SERVICE;

    private static final int CMD_SCAN_RESULTS_AVAILABLE              = BASE + 0;
    private static final int CMD_FULL_SCAN_RESULTS                   = BASE + 1;
    private static final int CMD_DRIVER_LOADED                       = BASE + 6;
    private static final int CMD_DRIVER_UNLOADED                     = BASE + 7;
    private static final int CMD_SCAN_PAUSED                         = BASE + 8;
    private static final int CMD_SCAN_RESTARTED                      = BASE + 9;
    private static final int CMD_SCAN_FAILED                         = BASE + 10;
    private static final int CMD_PNO_NETWORK_FOUND                   = BASE + 11;
    private static final int CMD_PNO_SCAN_FAILED                     = BASE + 12;

    private final Context mContext;
    private final Looper mLooper;
    private final WifiScannerImpl.WifiScannerImplFactory mScannerImplFactory;
    private final ArrayMap<Messenger, ClientInfo> mClients;

    private final RequestList<Void> mSingleScanListeners = new RequestList<>();

    private ChannelHelper mChannelHelper;
    private BackgroundScanScheduler mBackgroundScheduler;
    private WifiNative.ScanSettings mPreviousSchedule;

    private WifiBackgroundScanStateMachine mBackgroundScanStateMachine;
    private WifiSingleScanStateMachine mSingleScanStateMachine;
    private WifiPnoScanStateMachine mPnoScanStateMachine;
    private ClientHandler mClientHandler;
    // This is retrieved lazily because location service is started after wifi scanner.
    private final IBatteryStats mBatteryStats;
    private final AlarmManager mAlarmManager;
    private final WifiMetrics mWifiMetrics;
    private final Clock mClock;
    private final FrameworkFacade mFrameworkFacade;
    private final WifiPermissionsUtil mWifiPermissionsUtil;

    WifiScanningServiceImpl(Context context, Looper looper,
            WifiScannerImpl.WifiScannerImplFactory scannerImplFactory, IBatteryStats batteryStats,
            WifiInjector wifiInjector) {
        mContext = context;
        mLooper = looper;
        mScannerImplFactory = scannerImplFactory;
        mBatteryStats = batteryStats;
        mClients = new ArrayMap<>();
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mWifiMetrics = wifiInjector.getWifiMetrics();
        mClock = wifiInjector.getClock();
        mLog = wifiInjector.makeLog(TAG);
        mFrameworkFacade = wifiInjector.getFrameworkFacade();
        mWifiPermissionsUtil = wifiInjector.getWifiPermissionsUtil();
        mPreviousSchedule = null;
    }

    public void startService() {
        mBackgroundScanStateMachine = new WifiBackgroundScanStateMachine(mLooper);
        mSingleScanStateMachine = new WifiSingleScanStateMachine(mLooper);
        mPnoScanStateMachine = new WifiPnoScanStateMachine(mLooper);

        mBackgroundScanStateMachine.start();
        mSingleScanStateMachine.start();
        mPnoScanStateMachine.start();

        // Create client handler only after StateMachines are ready.
        mClientHandler = new ClientHandler(TAG, mLooper);
    }

    /**
     * Provide a way for unit tests to set valid log object in the WifiHandler
     * @param log WifiLog object to assign to the clientHandler
     */
    @VisibleForTesting
    public void setWifiHandlerLogForTest(WifiLog log) {
        mClientHandler.setWifiLog(log);
    }

    private WorkSource computeWorkSource(ClientInfo ci, WorkSource requestedWorkSource) {
        if (requestedWorkSource != null) {
            requestedWorkSource.clearNames();

            if (!requestedWorkSource.isEmpty()) {
                return requestedWorkSource;
            }
        }

        if (ci.getUid() > 0) {
            return new WorkSource(ci.getUid());
        }

        // We can't construct a sensible WorkSource because the one supplied to us was empty and
        // we don't have a valid UID for the given client.
        loge("Unable to compute workSource for client: " + ci + ", requested: "
                + requestedWorkSource);
        return new WorkSource();
    }

    private class RequestInfo<T> {
        final ClientInfo clientInfo;
        final int handlerId;
        final WorkSource workSource;
        final T settings;

        RequestInfo(ClientInfo clientInfo, int handlerId, WorkSource requestedWorkSource,
                T settings) {
            this.clientInfo = clientInfo;
            this.handlerId = handlerId;
            this.settings = settings;
            this.workSource = computeWorkSource(clientInfo, requestedWorkSource);
        }

        void reportEvent(int what, int arg1, Object obj) {
            clientInfo.reportEvent(what, arg1, handlerId, obj);
        }
    }

    private class RequestList<T> extends ArrayList<RequestInfo<T>> {
        void addRequest(ClientInfo ci, int handler, WorkSource reqworkSource, T settings) {
            add(new RequestInfo<T>(ci, handler, reqworkSource, settings));
        }

        T removeRequest(ClientInfo ci, int handlerId) {
            T removed = null;
            Iterator<RequestInfo<T>> iter = iterator();
            while (iter.hasNext()) {
                RequestInfo<T> entry = iter.next();
                if (entry.clientInfo == ci && entry.handlerId == handlerId) {
                    removed = entry.settings;
                    iter.remove();
                }
            }
            return removed;
        }

        Collection<T> getAllSettings() {
            ArrayList<T> settingsList = new ArrayList<>();
            Iterator<RequestInfo<T>> iter = iterator();
            while (iter.hasNext()) {
                RequestInfo<T> entry = iter.next();
                settingsList.add(entry.settings);
            }
            return settingsList;
        }

        Collection<T> getAllSettingsForClient(ClientInfo ci) {
            ArrayList<T> settingsList = new ArrayList<>();
            Iterator<RequestInfo<T>> iter = iterator();
            while (iter.hasNext()) {
                RequestInfo<T> entry = iter.next();
                if (entry.clientInfo == ci) {
                    settingsList.add(entry.settings);
                }
            }
            return settingsList;
        }

        void removeAllForClient(ClientInfo ci) {
            Iterator<RequestInfo<T>> iter = iterator();
            while (iter.hasNext()) {
                RequestInfo<T> entry = iter.next();
                if (entry.clientInfo == ci) {
                    iter.remove();
                }
            }
        }

        WorkSource createMergedWorkSource() {
            WorkSource mergedSource = new WorkSource();
            for (RequestInfo<T> entry : this) {
                mergedSource.add(entry.workSource);
            }
            return mergedSource;
        }
    }

    /**
     * State machine that holds the state of single scans. Scans should only be active in the
     * ScanningState. The pending scans and active scans maps are swapped when entering
     * ScanningState. Any requests queued while scanning will be placed in the pending queue and
     * executed after transitioning back to IdleState.
     */
    class WifiSingleScanStateMachine extends StateMachine implements WifiNative.ScanEventHandler {
        /**
         * Maximum age of results that we return from our cache via
         * {@link WifiScanner#getScanResults()}.
         * This is currently set to 3 minutes to restore parity with the wpa_supplicant's scan
         * result cache expiration policy. (See b/62253332 for details)
         */
        @VisibleForTesting
        public static final int CACHED_SCAN_RESULTS_MAX_AGE_IN_MILLIS = 180 * 1000;

        private final DefaultState mDefaultState = new DefaultState();
        private final DriverStartedState mDriverStartedState = new DriverStartedState();
        private final IdleState  mIdleState  = new IdleState();
        private final ScanningState  mScanningState  = new ScanningState();

        private WifiNative.ScanSettings mActiveScanSettings = null;
        private RequestList<ScanSettings> mActiveScans = new RequestList<>();
        private RequestList<ScanSettings> mPendingScans = new RequestList<>();

        // Scan results cached from the last full single scan request.
        private final List<ScanResult> mCachedScanResults = new ArrayList<>();

        WifiSingleScanStateMachine(Looper looper) {
            super("WifiSingleScanStateMachine", looper);

            setLogRecSize(128);
            setLogOnlyTransitions(false);

            // CHECKSTYLE:OFF IndentationCheck
            addState(mDefaultState);
                addState(mDriverStartedState, mDefaultState);
                    addState(mIdleState, mDriverStartedState);
                    addState(mScanningState, mDriverStartedState);
            // CHECKSTYLE:ON IndentationCheck

            setInitialState(mDefaultState);
        }

        /**
         * Called to indicate a change in state for the current scan.
         * Will dispatch a coresponding event to the state machine
         */
        @Override
        public void onScanStatus(int event) {
            if (DBG) localLog("onScanStatus event received, event=" + event);
            switch(event) {
                case WifiNative.WIFI_SCAN_RESULTS_AVAILABLE:
                case WifiNative.WIFI_SCAN_THRESHOLD_NUM_SCANS:
                case WifiNative.WIFI_SCAN_THRESHOLD_PERCENT:
                    sendMessage(CMD_SCAN_RESULTS_AVAILABLE);
                    break;
                case WifiNative.WIFI_SCAN_FAILED:
                    sendMessage(CMD_SCAN_FAILED);
                    break;
                default:
                    Log.e(TAG, "Unknown scan status event: " + event);
                    break;
            }
        }

        /**
         * Called for each full scan result if requested
         */
        @Override
        public void onFullScanResult(ScanResult fullScanResult, int bucketsScanned) {
            if (DBG) localLog("onFullScanResult received");
            sendMessage(CMD_FULL_SCAN_RESULTS, 0, bucketsScanned, fullScanResult);
        }

        @Override
        public void onScanPaused(ScanData[] scanData) {
            // should not happen for single scan
            Log.e(TAG, "Got scan paused for single scan");
        }

        @Override
        public void onScanRestarted() {
            // should not happen for single scan
            Log.e(TAG, "Got scan restarted for single scan");
        }

        class DefaultState extends State {
            @Override
            public void enter() {
                mActiveScans.clear();
                mPendingScans.clear();
            }
            @Override
            public boolean processMessage(Message msg) {
                switch (msg.what) {
                    case CMD_DRIVER_LOADED:
                        if (mScannerImpl == null) {
                            loge("Failed to start single scan state machine because scanner impl"
                                    + " is null");
                            return HANDLED;
                        }
                        transitionTo(mIdleState);
                        return HANDLED;
                    case CMD_DRIVER_UNLOADED:
                        transitionTo(mDefaultState);
                        return HANDLED;
                    case WifiScanner.CMD_START_SINGLE_SCAN:
                    case WifiScanner.CMD_STOP_SINGLE_SCAN:
                        replyFailed(msg, WifiScanner.REASON_UNSPECIFIED, "not available");
                        return HANDLED;
                    case CMD_SCAN_RESULTS_AVAILABLE:
                        if (DBG) localLog("ignored scan results available event");
                        return HANDLED;
                    case CMD_FULL_SCAN_RESULTS:
                        if (DBG) localLog("ignored full scan result event");
                        return HANDLED;
                    case WifiScanner.CMD_GET_SINGLE_SCAN_RESULTS:
                        msg.obj = new WifiScanner.ParcelableScanResults(
                            filterCachedScanResultsByAge());
                        replySucceeded(msg);
                        return HANDLED;
                    default:
                        return NOT_HANDLED;
                }
            }

            /**
             * Filter out  any scan results that are older than
             * {@link #CACHED_SCAN_RESULTS_MAX_AGE_IN_MILLIS}.
             *
             * @return Filtered list of scan results.
             */
            private ScanResult[] filterCachedScanResultsByAge() {
                // Using ScanResult.timestamp here to ensure that we use the same fields as
                // WificondScannerImpl for filtering stale results.
                long currentTimeInMillis = mClock.getElapsedSinceBootMillis();
                return mCachedScanResults.stream()
                        .filter(scanResult
                                -> ((currentTimeInMillis - (scanResult.timestamp / 1000))
                                        < CACHED_SCAN_RESULTS_MAX_AGE_IN_MILLIS))
                        .toArray(ScanResult[]::new);
            }
        }

        /**
         * State representing when the driver is running. This state is not meant to be transitioned
         * directly, but is instead intended as a parent state of ScanningState and IdleState
         * to hold common functionality and handle cleaning up scans when the driver is shut down.
         */
        class DriverStartedState extends State {
            @Override
            public void exit() {
                // clear scan results when scan mode is not active
                mCachedScanResults.clear();

                mWifiMetrics.incrementScanReturnEntry(
                        WifiMetricsProto.WifiLog.SCAN_FAILURE_INTERRUPTED,
                        mPendingScans.size());
                sendOpFailedToAllAndClear(mPendingScans, WifiScanner.REASON_UNSPECIFIED,
                        "Scan was interrupted");
            }

            @Override
            public boolean processMessage(Message msg) {
                ClientInfo ci = mClients.get(msg.replyTo);

                switch (msg.what) {
                    case CMD_DRIVER_LOADED:
                        // Ignore if we're already in driver loaded state.
                        return HANDLED;
                    case WifiScanner.CMD_START_SINGLE_SCAN:
                        int handler = msg.arg2;
                        Bundle scanParams = (Bundle) msg.obj;
                        if (scanParams == null) {
                            logCallback("singleScanInvalidRequest",  ci, handler, "null params");
                            replyFailed(msg, WifiScanner.REASON_INVALID_REQUEST, "params null");
                            return HANDLED;
                        }
                        scanParams.setDefusable(true);
                        ScanSettings scanSettings =
                                scanParams.getParcelable(WifiScanner.SCAN_PARAMS_SCAN_SETTINGS_KEY);
                        WorkSource workSource =
                                scanParams.getParcelable(WifiScanner.SCAN_PARAMS_WORK_SOURCE_KEY);
                        if (validateScanRequest(ci, handler, scanSettings)) {
                            mWifiMetrics.incrementOneshotScanCount();
                            if (scanSettings.band == WifiScanner.WIFI_BAND_5_GHZ_DFS_ONLY
                                    || scanSettings.band == WifiScanner.WIFI_BAND_5_GHZ_WITH_DFS
                                    || scanSettings.band == WifiScanner.WIFI_BAND_BOTH_WITH_DFS) {
                                mWifiMetrics.incrementOneshotScanWithDfsCount();
                            }
                            logScanRequest("addSingleScanRequest", ci, handler, workSource,
                                    scanSettings, null);
                            replySucceeded(msg);

                            // If there is an active scan that will fulfill the scan request then
                            // mark this request as an active scan, otherwise mark it pending.
                            // If were not currently scanning then try to start a scan. Otherwise
                            // this scan will be scheduled when transitioning back to IdleState
                            // after finishing the current scan.
                            if (getCurrentState() == mScanningState) {
                                if (activeScanSatisfies(scanSettings)) {
                                    mActiveScans.addRequest(ci, handler, workSource, scanSettings);
                                } else {
                                    mPendingScans.addRequest(ci, handler, workSource, scanSettings);
                                }
                            } else {
                                mPendingScans.addRequest(ci, handler, workSource, scanSettings);
                                tryToStartNewScan();
                            }
                        } else {
                            logCallback("singleScanInvalidRequest",  ci, handler, "bad request");
                            replyFailed(msg, WifiScanner.REASON_INVALID_REQUEST, "bad request");
                            mWifiMetrics.incrementScanReturnEntry(
                                    WifiMetricsProto.WifiLog.SCAN_FAILURE_INVALID_CONFIGURATION, 1);
                        }
                        return HANDLED;
                    case WifiScanner.CMD_STOP_SINGLE_SCAN:
                        removeSingleScanRequest(ci, msg.arg2);
                        return HANDLED;
                    default:
                        return NOT_HANDLED;
                }
            }
        }

        class IdleState extends State {
            @Override
            public void enter() {
                tryToStartNewScan();
            }

            @Override
            public boolean processMessage(Message msg) {
                return NOT_HANDLED;
            }
        }

        class ScanningState extends State {
            private WorkSource mScanWorkSource;

            @Override
            public void enter() {
                mScanWorkSource = mActiveScans.createMergedWorkSource();
                try {
                    mBatteryStats.noteWifiScanStartedFromSource(mScanWorkSource);
                } catch (RemoteException e) {
                    loge(e.toString());
                }
                StatsLog.write(StatsLog.WIFI_SCAN_STATE_CHANGED, mScanWorkSource,
                        StatsLog.WIFI_SCAN_STATE_CHANGED__STATE__ON);
            }

            @Override
            public void exit() {
                mActiveScanSettings = null;
                try {
                    mBatteryStats.noteWifiScanStoppedFromSource(mScanWorkSource);
                } catch (RemoteException e) {
                    loge(e.toString());
                }
                StatsLog.write(StatsLog.WIFI_SCAN_STATE_CHANGED, mScanWorkSource,
                        StatsLog.WIFI_SCAN_STATE_CHANGED__STATE__OFF);

                // if any scans are still active (never got results available then indicate failure)
                mWifiMetrics.incrementScanReturnEntry(
                                WifiMetricsProto.WifiLog.SCAN_UNKNOWN,
                                mActiveScans.size());
                sendOpFailedToAllAndClear(mActiveScans, WifiScanner.REASON_UNSPECIFIED,
                        "Scan was interrupted");
            }

            @Override
            public boolean processMessage(Message msg) {
                switch (msg.what) {
                    case CMD_SCAN_RESULTS_AVAILABLE:
                        mWifiMetrics.incrementScanReturnEntry(
                                WifiMetricsProto.WifiLog.SCAN_SUCCESS,
                                mActiveScans.size());
                        reportScanResults(mScannerImpl.getLatestSingleScanResults());
                        mActiveScans.clear();
                        transitionTo(mIdleState);
                        return HANDLED;
                    case CMD_FULL_SCAN_RESULTS:
                        reportFullScanResult((ScanResult) msg.obj, /* bucketsScanned */ msg.arg2);
                        return HANDLED;
                    case CMD_SCAN_FAILED:
                        mWifiMetrics.incrementScanReturnEntry(
                                WifiMetricsProto.WifiLog.SCAN_UNKNOWN, mActiveScans.size());
                        sendOpFailedToAllAndClear(mActiveScans, WifiScanner.REASON_UNSPECIFIED,
                                "Scan failed");
                        transitionTo(mIdleState);
                        return HANDLED;
                    default:
                        return NOT_HANDLED;
                }
            }
        }

        boolean validateScanType(int type) {
            return (type == WifiScanner.TYPE_LOW_LATENCY || type == WifiScanner.TYPE_LOW_POWER
                    || type == WifiScanner.TYPE_HIGH_ACCURACY);
        }

        boolean validateScanRequest(ClientInfo ci, int handler, ScanSettings settings) {
            if (ci == null) {
                Log.d(TAG, "Failing single scan request ClientInfo not found " + handler);
                return false;
            }
            if (settings.band == WifiScanner.WIFI_BAND_UNSPECIFIED) {
                if (settings.channels == null || settings.channels.length == 0) {
                    Log.d(TAG, "Failing single scan because channel list was empty");
                    return false;
                }
            }
            if (!validateScanType(settings.type)) {
                Log.e(TAG, "Invalid scan type " + settings.type);
                return false;
            }
            if (mContext.checkPermission(
                    Manifest.permission.NETWORK_STACK, UNKNOWN_PID, ci.getUid())
                    == PERMISSION_DENIED) {
                if (!ArrayUtils.isEmpty(settings.hiddenNetworks)) {
                    Log.e(TAG, "Failing single scan because app " + ci.getUid()
                            + " does not have permission to set hidden networks");
                    return false;
                }
                if (settings.type != WifiScanner.TYPE_LOW_LATENCY) {
                    Log.e(TAG, "Failing single scan because app " + ci.getUid()
                            + " does not have permission to set type");
                    return false;
                }
            }
            return true;
        }

        int getNativeScanType(int type) {
            switch(type) {
                case WifiScanner.TYPE_LOW_LATENCY:
                    return WifiNative.SCAN_TYPE_LOW_LATENCY;
                case WifiScanner.TYPE_LOW_POWER:
                    return WifiNative.SCAN_TYPE_LOW_POWER;
                case WifiScanner.TYPE_HIGH_ACCURACY:
                    return WifiNative.SCAN_TYPE_HIGH_ACCURACY;
                default:
                    // This should never happen becuase we've validated the incoming type in
                    // |validateScanType|.
                    throw new IllegalArgumentException("Invalid scan type " + type);
            }
        }

        // We can coalesce a LOW_POWER/LOW_LATENCY scan request into an ongoing HIGH_ACCURACY
        // scan request. But, we can't coalesce a HIGH_ACCURACY scan request into an ongoing
        // LOW_POWER/LOW_LATENCY scan request.
        boolean activeScanTypeSatisfies(int requestScanType) {
            switch(mActiveScanSettings.scanType) {
                case WifiNative.SCAN_TYPE_LOW_LATENCY:
                case WifiNative.SCAN_TYPE_LOW_POWER:
                    return requestScanType != WifiNative.SCAN_TYPE_HIGH_ACCURACY;
                case WifiNative.SCAN_TYPE_HIGH_ACCURACY:
                    return true;
                default:
                    // This should never happen becuase we've validated the incoming type in
                    // |validateScanType|.
                    throw new IllegalArgumentException("Invalid scan type "
                        + mActiveScanSettings.scanType);
            }
        }

        // If there is a HIGH_ACCURACY scan request among the requests being merged, the merged
        // scan type should be HIGH_ACCURACY.
        int mergeScanTypes(int existingScanType, int newScanType) {
            switch(existingScanType) {
                case WifiNative.SCAN_TYPE_LOW_LATENCY:
                case WifiNative.SCAN_TYPE_LOW_POWER:
                    return newScanType;
                case WifiNative.SCAN_TYPE_HIGH_ACCURACY:
                    return existingScanType;
                default:
                    // This should never happen becuase we've validated the incoming type in
                    // |validateScanType|.
                    throw new IllegalArgumentException("Invalid scan type " + existingScanType);
            }
        }

        boolean activeScanSatisfies(ScanSettings settings) {
            if (mActiveScanSettings == null) {
                return false;
            }

            if (!activeScanTypeSatisfies(getNativeScanType(settings.type))) {
                return false;
            }

            // there is always one bucket for a single scan
            WifiNative.BucketSettings activeBucket = mActiveScanSettings.buckets[0];

            // validate that all requested channels are being scanned
            ChannelCollection activeChannels = mChannelHelper.createChannelCollection();
            activeChannels.addChannels(activeBucket);
            if (!activeChannels.containsSettings(settings)) {
                return false;
            }

            // if the request is for a full scan, but there is no ongoing full scan
            if ((settings.reportEvents & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT) != 0
                    && (activeBucket.report_events & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT)
                    == 0) {
                return false;
            }

            if (!ArrayUtils.isEmpty(settings.hiddenNetworks)) {
                if (ArrayUtils.isEmpty(mActiveScanSettings.hiddenNetworks)) {
                    return false;
                }
                List<WifiNative.HiddenNetwork> activeHiddenNetworks = new ArrayList<>();
                for (WifiNative.HiddenNetwork hiddenNetwork : mActiveScanSettings.hiddenNetworks) {
                    activeHiddenNetworks.add(hiddenNetwork);
                }
                for (ScanSettings.HiddenNetwork hiddenNetwork : settings.hiddenNetworks) {
                    WifiNative.HiddenNetwork nativeHiddenNetwork = new WifiNative.HiddenNetwork();
                    nativeHiddenNetwork.ssid = hiddenNetwork.ssid;
                    if (!activeHiddenNetworks.contains(nativeHiddenNetwork)) {
                        return false;
                    }
                }
            }

            return true;
        }

        void removeSingleScanRequest(ClientInfo ci, int handler) {
            if (ci != null) {
                logScanRequest("removeSingleScanRequest", ci, handler, null, null, null);
                mPendingScans.removeRequest(ci, handler);
                mActiveScans.removeRequest(ci, handler);
            }
        }

        void removeSingleScanRequests(ClientInfo ci) {
            if (ci != null) {
                logScanRequest("removeSingleScanRequests", ci, -1, null, null, null);
                mPendingScans.removeAllForClient(ci);
                mActiveScans.removeAllForClient(ci);
            }
        }

        void tryToStartNewScan() {
            if (mPendingScans.size() == 0) { // no pending requests
                return;
            }
            mChannelHelper.updateChannels();
            // TODO move merging logic to a scheduler
            WifiNative.ScanSettings settings = new WifiNative.ScanSettings();
            settings.num_buckets = 1;
            WifiNative.BucketSettings bucketSettings = new WifiNative.BucketSettings();
            bucketSettings.bucket = 0;
            bucketSettings.period_ms = 0;
            bucketSettings.report_events = WifiScanner.REPORT_EVENT_AFTER_EACH_SCAN;

            ChannelCollection channels = mChannelHelper.createChannelCollection();
            List<WifiNative.HiddenNetwork> hiddenNetworkList = new ArrayList<>();
            for (RequestInfo<ScanSettings> entry : mPendingScans) {
                settings.scanType =
                    mergeScanTypes(settings.scanType, getNativeScanType(entry.settings.type));
                channels.addChannels(entry.settings);
                if (entry.settings.hiddenNetworks != null) {
                    for (int i = 0; i < entry.settings.hiddenNetworks.length; i++) {
                        WifiNative.HiddenNetwork hiddenNetwork = new WifiNative.HiddenNetwork();
                        hiddenNetwork.ssid = entry.settings.hiddenNetworks[i].ssid;
                        hiddenNetworkList.add(hiddenNetwork);
                    }
                }
                if ((entry.settings.reportEvents & WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT)
                        != 0) {
                    bucketSettings.report_events |= WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT;
                }
            }
            if (hiddenNetworkList.size() > 0) {
                settings.hiddenNetworks = new WifiNative.HiddenNetwork[hiddenNetworkList.size()];
                int numHiddenNetworks = 0;
                for (WifiNative.HiddenNetwork hiddenNetwork : hiddenNetworkList) {
                    settings.hiddenNetworks[numHiddenNetworks++] = hiddenNetwork;
                }
            }

            channels.fillBucketSettings(bucketSettings, Integer.MAX_VALUE);

            settings.buckets = new WifiNative.BucketSettings[] {bucketSettings};
            if (mScannerImpl.startSingleScan(settings, this)) {
                // store the active scan settings
                mActiveScanSettings = settings;
                // swap pending and active scan requests
                RequestList<ScanSettings> tmp = mActiveScans;
                mActiveScans = mPendingScans;
                mPendingScans = tmp;
                // make sure that the pending list is clear
                mPendingScans.clear();
                transitionTo(mScanningState);
            } else {
                mWifiMetrics.incrementScanReturnEntry(
                        WifiMetricsProto.WifiLog.SCAN_UNKNOWN, mPendingScans.size());
                // notify and cancel failed scans
                sendOpFailedToAllAndClear(mPendingScans, WifiScanner.REASON_UNSPECIFIED,
                        "Failed to start single scan");
            }
        }

        void sendOpFailedToAllAndClear(RequestList<?> clientHandlers, int reason,
                String description) {
            for (RequestInfo<?> entry : clientHandlers) {
                logCallback("singleScanFailed",  entry.clientInfo, entry.handlerId,
                        "reason=" + reason + ", " + description);
                entry.reportEvent(WifiScanner.CMD_OP_FAILED, 0,
                        new WifiScanner.OperationResult(reason, description));
            }
            clientHandlers.clear();
        }

        void reportFullScanResult(ScanResult result, int bucketsScanned) {
            for (RequestInfo<ScanSettings> entry : mActiveScans) {
                if (ScanScheduleUtil.shouldReportFullScanResultForSettings(mChannelHelper,
                                result, bucketsScanned, entry.settings, -1)) {
                    entry.reportEvent(WifiScanner.CMD_FULL_SCAN_RESULT, 0, result);
                }
            }

            for (RequestInfo<Void> entry : mSingleScanListeners) {
                entry.reportEvent(WifiScanner.CMD_FULL_SCAN_RESULT, 0, result);
            }
        }

        void reportScanResults(ScanData results) {
            if (results != null && results.getResults() != null) {
                if (results.getResults().length > 0) {
                    mWifiMetrics.incrementNonEmptyScanResultCount();
                } else {
                    mWifiMetrics.incrementEmptyScanResultCount();
                }
            }
            ScanData[] allResults = new ScanData[] {results};
            for (RequestInfo<ScanSettings> entry : mActiveScans) {
                ScanData[] resultsToDeliver = ScanScheduleUtil.filterResultsForSettings(
                        mChannelHelper, allResults, entry.settings, -1);
                WifiScanner.ParcelableScanData parcelableResultsToDeliver =
                        new WifiScanner.ParcelableScanData(resultsToDeliver);
                logCallback("singleScanResults",  entry.clientInfo, entry.handlerId,
                        describeForLog(resultsToDeliver));
                entry.reportEvent(WifiScanner.CMD_SCAN_RESULT, 0, parcelableResultsToDeliver);
                // make sure the handler is removed
                entry.reportEvent(WifiScanner.CMD_SINGLE_SCAN_COMPLETED, 0, null);
            }

            WifiScanner.ParcelableScanData parcelableAllResults =
                    new WifiScanner.ParcelableScanData(allResults);
            for (RequestInfo<Void> entry : mSingleScanListeners) {
                logCallback("singleScanResults",  entry.clientInfo, entry.handlerId,
                        describeForLog(allResults));
                entry.reportEvent(WifiScanner.CMD_SCAN_RESULT, 0, parcelableAllResults);
            }

            // Cache full band (with DFS or not) scan results.
            if (results.getBandScanned() == WifiScanner.WIFI_BAND_BOTH_WITH_DFS
                    || results.getBandScanned() == WifiScanner.WIFI_BAND_BOTH) {
                mCachedScanResults.clear();
                mCachedScanResults.addAll(Arrays.asList(results.getResults()));
            }
        }

        List<ScanResult> getCachedScanResultsAsList() {
            return mCachedScanResults;
        }
    }

    class WifiBackgroundScanStateMachine extends StateMachine
            implements WifiNative.ScanEventHandler {

        private final DefaultState mDefaultState = new DefaultState();
        private final StartedState mStartedState = new StartedState();
        private final PausedState  mPausedState  = new PausedState();

        private final RequestList<ScanSettings> mActiveBackgroundScans = new RequestList<>();

        WifiBackgroundScanStateMachine(Looper looper) {
            super("WifiBackgroundScanStateMachine", looper);

            setLogRecSize(512);
            setLogOnlyTransitions(false);

            // CHECKSTYLE:OFF IndentationCheck
            addState(mDefaultState);
                addState(mStartedState, mDefaultState);
                addState(mPausedState, mDefaultState);
            // CHECKSTYLE:ON IndentationCheck

            setInitialState(mDefaultState);
        }

        public Collection<ScanSettings> getBackgroundScanSettings(ClientInfo ci) {
            return mActiveBackgroundScans.getAllSettingsForClient(ci);
        }

        public void removeBackgroundScanSettings(ClientInfo ci) {
            mActiveBackgroundScans.removeAllForClient(ci);
            updateSchedule();
        }

        @Override
        public void onScanStatus(int event) {
            if (DBG) localLog("onScanStatus event received, event=" + event);
            switch(event) {
                case WifiNative.WIFI_SCAN_RESULTS_AVAILABLE:
                case WifiNative.WIFI_SCAN_THRESHOLD_NUM_SCANS:
                case WifiNative.WIFI_SCAN_THRESHOLD_PERCENT:
                    sendMessage(CMD_SCAN_RESULTS_AVAILABLE);
                    break;
                case WifiNative.WIFI_SCAN_FAILED:
                    sendMessage(CMD_SCAN_FAILED);
                    break;
                default:
                    Log.e(TAG, "Unknown scan status event: " + event);
                    break;
            }
        }

        @Override
        public void onFullScanResult(ScanResult fullScanResult, int bucketsScanned) {
            if (DBG) localLog("onFullScanResult received");
            sendMessage(CMD_FULL_SCAN_RESULTS, 0, bucketsScanned, fullScanResult);
        }

        @Override
        public void onScanPaused(ScanData scanData[]) {
            if (DBG) localLog("onScanPaused received");
            sendMessage(CMD_SCAN_PAUSED, scanData);
        }

        @Override
        public void onScanRestarted() {
            if (DBG) localLog("onScanRestarted received");
            sendMessage(CMD_SCAN_RESTARTED);
        }

        class DefaultState extends State {
            @Override
            public void enter() {
                if (DBG) localLog("DefaultState");
                mActiveBackgroundScans.clear();
            }

            @Override
            public boolean processMessage(Message msg) {
                switch (msg.what) {
                    case CMD_DRIVER_LOADED:
                        // TODO this should be moved to a common location since it is used outside
                        // of this state machine. It is ok right now because the driver loaded event
                        // is sent to this state machine first.
                        mScannerImpl = mScannerImplFactory.create(mContext, mLooper, mClock);
                        if (mScannerImpl == null) {
                            loge("Failed to start bgscan scan state machine because scanner impl"
                                    + " is null");
                            return HANDLED;
                        }
                        mChannelHelper = mScannerImpl.getChannelHelper();

                        mBackgroundScheduler = new BackgroundScanScheduler(mChannelHelper);

                        WifiNative.ScanCapabilities capabilities =
                                new WifiNative.ScanCapabilities();
                        if (!mScannerImpl.getScanCapabilities(capabilities)) {
                            loge("could not get scan capabilities");
                            return HANDLED;
                        }
                        if (capabilities.max_scan_buckets <= 0) {
                            loge("invalid max buckets in scan capabilities "
                                    + capabilities.max_scan_buckets);
                            return HANDLED;
                        }
                        mBackgroundScheduler.setMaxBuckets(capabilities.max_scan_buckets);
                        mBackgroundScheduler.setMaxApPerScan(capabilities.max_ap_cache_per_scan);

                        Log.i(TAG, "wifi driver loaded with scan capabilities: "
                                + "max buckets=" + capabilities.max_scan_buckets);

                        transitionTo(mStartedState);
                        return HANDLED;
                    case CMD_DRIVER_UNLOADED:
                        Log.i(TAG, "wifi driver unloaded");
                        transitionTo(mDefaultState);
                        break;
                    case WifiScanner.CMD_START_BACKGROUND_SCAN:
                    case WifiScanner.CMD_STOP_BACKGROUND_SCAN:
                    case WifiScanner.CMD_START_SINGLE_SCAN:
                    case WifiScanner.CMD_STOP_SINGLE_SCAN:
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
            public void exit() {
                sendBackgroundScanFailedToAllAndClear(
                        WifiScanner.REASON_UNSPECIFIED, "Scan was interrupted");
                if (mScannerImpl != null) {
                    mScannerImpl.cleanup();
                }
            }

            @Override
            public boolean processMessage(Message msg) {
                ClientInfo ci = mClients.get(msg.replyTo);

                switch (msg.what) {
                    case CMD_DRIVER_LOADED:
                        Log.e(TAG, "wifi driver loaded received while already loaded");
                        // Ignore if we're already in driver loaded state.
                        return HANDLED;
                    case CMD_DRIVER_UNLOADED:
                        return NOT_HANDLED;
                    case WifiScanner.CMD_START_BACKGROUND_SCAN: {
                        mWifiMetrics.incrementBackgroundScanCount();
                        Bundle scanParams = (Bundle) msg.obj;
                        if (scanParams == null) {
                            replyFailed(msg, WifiScanner.REASON_INVALID_REQUEST, "params null");
                            return HANDLED;
                        }
                        scanParams.setDefusable(true);
                        ScanSettings scanSettings =
                                scanParams.getParcelable(WifiScanner.SCAN_PARAMS_SCAN_SETTINGS_KEY);
                        WorkSource workSource =
                                scanParams.getParcelable(WifiScanner.SCAN_PARAMS_WORK_SOURCE_KEY);
                        if (addBackgroundScanRequest(ci, msg.arg2, scanSettings, workSource)) {
                            replySucceeded(msg);
                        } else {
                            replyFailed(msg, WifiScanner.REASON_INVALID_REQUEST, "bad request");
                        }
                        break;
                    }
                    case WifiScanner.CMD_STOP_BACKGROUND_SCAN:
                        removeBackgroundScanRequest(ci, msg.arg2);
                        break;
                    case WifiScanner.CMD_GET_SCAN_RESULTS:
                        reportScanResults(mScannerImpl.getLatestBatchedScanResults(true));
                        replySucceeded(msg);
                        break;
                    case CMD_SCAN_RESULTS_AVAILABLE:
                        reportScanResults(mScannerImpl.getLatestBatchedScanResults(true));
                        break;
                    case CMD_FULL_SCAN_RESULTS:
                        reportFullScanResult((ScanResult) msg.obj, /* bucketsScanned */ msg.arg2);
                        break;
                    case CMD_SCAN_PAUSED:
                        reportScanResults((ScanData[]) msg.obj);
                        transitionTo(mPausedState);
                        break;
                    case CMD_SCAN_FAILED:
                        Log.e(TAG, "WifiScanner background scan gave CMD_SCAN_FAILED");
                        sendBackgroundScanFailedToAllAndClear(
                                WifiScanner.REASON_UNSPECIFIED, "Background Scan failed");
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

        private boolean addBackgroundScanRequest(ClientInfo ci, int handler,
                ScanSettings settings, WorkSource workSource) {
            // sanity check the input
            if (ci == null) {
                Log.d(TAG, "Failing scan request ClientInfo not found " + handler);
                return false;
            }
            if (settings.periodInMs < WifiScanner.MIN_SCAN_PERIOD_MS) {
                loge("Failing scan request because periodInMs is " + settings.periodInMs
                        + ", min scan period is: " + WifiScanner.MIN_SCAN_PERIOD_MS);
                return false;
            }

            if (settings.band == WifiScanner.WIFI_BAND_UNSPECIFIED && settings.channels == null) {
                loge("Channels was null with unspecified band");
                return false;
            }

            if (settings.band == WifiScanner.WIFI_BAND_UNSPECIFIED
                    && settings.channels.length == 0) {
                loge("No channels specified");
                return false;
            }

            int minSupportedPeriodMs = mChannelHelper.estimateScanDuration(settings);
            if (settings.periodInMs < minSupportedPeriodMs) {
                loge("Failing scan request because minSupportedPeriodMs is "
                        + minSupportedPeriodMs + " but the request wants " + settings.periodInMs);
                return false;
            }

            // check truncated binary exponential back off scan settings
            if (settings.maxPeriodInMs != 0 && settings.maxPeriodInMs != settings.periodInMs) {
                if (settings.maxPeriodInMs < settings.periodInMs) {
                    loge("Failing scan request because maxPeriodInMs is " + settings.maxPeriodInMs
                            + " but less than periodInMs " + settings.periodInMs);
                    return false;
                }
                if (settings.maxPeriodInMs > WifiScanner.MAX_SCAN_PERIOD_MS) {
                    loge("Failing scan request because maxSupportedPeriodMs is "
                            + WifiScanner.MAX_SCAN_PERIOD_MS + " but the request wants "
                            + settings.maxPeriodInMs);
                    return false;
                }
                if (settings.stepCount < 1) {
                    loge("Failing scan request because stepCount is " + settings.stepCount
                            + " which is less than 1");
                    return false;
                }
            }

            logScanRequest("addBackgroundScanRequest", ci, handler, null, settings, null);
            mActiveBackgroundScans.addRequest(ci, handler, workSource, settings);

            if (updateSchedule()) {
                return true;
            } else {
                mActiveBackgroundScans.removeRequest(ci, handler);
                localLog("Failing scan request because failed to reset scan");
                return false;
            }
        }

        private boolean updateSchedule() {
            if (mChannelHelper == null || mBackgroundScheduler == null || mScannerImpl == null) {
                loge("Failed to update schedule because WifiScanningService is not initialized");
                return false;
            }
            mChannelHelper.updateChannels();
            Collection<ScanSettings> settings = mActiveBackgroundScans.getAllSettings();

            mBackgroundScheduler.updateSchedule(settings);
            WifiNative.ScanSettings schedule = mBackgroundScheduler.getSchedule();

            if (ScanScheduleUtil.scheduleEquals(mPreviousSchedule, schedule)) {
                if (DBG) Log.d(TAG, "schedule updated with no change");
                return true;
            }

            mPreviousSchedule = schedule;

            if (schedule.num_buckets == 0) {
                mScannerImpl.stopBatchedScan();
                if (DBG) Log.d(TAG, "scan stopped");
                return true;
            } else {
                localLog("starting scan: "
                        + "base period=" + schedule.base_period_ms
                        + ", max ap per scan=" + schedule.max_ap_per_scan
                        + ", batched scans=" + schedule.report_threshold_num_scans);
                for (int b = 0; b < schedule.num_buckets; b++) {
                    WifiNative.BucketSettings bucket = schedule.buckets[b];
                    localLog("bucket " + bucket.bucket + " (" + bucket.period_ms + "ms)"
                            + "[" + bucket.report_events + "]: "
                            + ChannelHelper.toString(bucket));
                }

                if (mScannerImpl.startBatchedScan(schedule, this)) {
                    if (DBG) {
                        Log.d(TAG, "scan restarted with " + schedule.num_buckets
                                + " bucket(s) and base period: " + schedule.base_period_ms);
                    }
                    return true;
                } else {
                    mPreviousSchedule = null;
                    loge("error starting scan: "
                            + "base period=" + schedule.base_period_ms
                            + ", max ap per scan=" + schedule.max_ap_per_scan
                            + ", batched scans=" + schedule.report_threshold_num_scans);
                    for (int b = 0; b < schedule.num_buckets; b++) {
                        WifiNative.BucketSettings bucket = schedule.buckets[b];
                        loge("bucket " + bucket.bucket + " (" + bucket.period_ms + "ms)"
                                + "[" + bucket.report_events + "]: "
                                + ChannelHelper.toString(bucket));
                    }
                    return false;
                }
            }
        }

        private void removeBackgroundScanRequest(ClientInfo ci, int handler) {
            if (ci != null) {
                ScanSettings settings = mActiveBackgroundScans.removeRequest(ci, handler);
                logScanRequest("removeBackgroundScanRequest", ci, handler, null, settings, null);
                updateSchedule();
            }
        }

        private void reportFullScanResult(ScanResult result, int bucketsScanned) {
            for (RequestInfo<ScanSettings> entry : mActiveBackgroundScans) {
                ClientInfo ci = entry.clientInfo;
                int handler = entry.handlerId;
                ScanSettings settings = entry.settings;
                if (mBackgroundScheduler.shouldReportFullScanResultForSettings(
                                result, bucketsScanned, settings)) {
                    ScanResult newResult = new ScanResult(result);
                    if (result.informationElements != null) {
                        newResult.informationElements = result.informationElements.clone();
                    }
                    else {
                        newResult.informationElements = null;
                    }
                    ci.reportEvent(WifiScanner.CMD_FULL_SCAN_RESULT, 0, handler, newResult);
                }
            }
        }

        private void reportScanResults(ScanData[] results) {
            if (results == null) {
                Log.d(TAG,"The results is null, nothing to report.");
                return;
            }
            for (ScanData result : results) {
                if (result != null && result.getResults() != null) {
                    if (result.getResults().length > 0) {
                        mWifiMetrics.incrementNonEmptyScanResultCount();
                    } else {
                        mWifiMetrics.incrementEmptyScanResultCount();
                    }
                }
            }
            for (RequestInfo<ScanSettings> entry : mActiveBackgroundScans) {
                ClientInfo ci = entry.clientInfo;
                int handler = entry.handlerId;
                ScanSettings settings = entry.settings;
                ScanData[] resultsToDeliver =
                        mBackgroundScheduler.filterResultsForSettings(results, settings);
                if (resultsToDeliver != null) {
                    logCallback("backgroundScanResults", ci, handler,
                            describeForLog(resultsToDeliver));
                    WifiScanner.ParcelableScanData parcelableScanData =
                            new WifiScanner.ParcelableScanData(resultsToDeliver);
                    ci.reportEvent(WifiScanner.CMD_SCAN_RESULT, 0, handler, parcelableScanData);
                }
            }
        }

        private void sendBackgroundScanFailedToAllAndClear(int reason, String description) {
            for (RequestInfo<ScanSettings> entry : mActiveBackgroundScans) {
                ClientInfo ci = entry.clientInfo;
                int handler = entry.handlerId;
                ci.reportEvent(WifiScanner.CMD_OP_FAILED, 0, handler,
                        new WifiScanner.OperationResult(reason, description));
            }
            mActiveBackgroundScans.clear();
        }
    }

    /**
     * PNO scan state machine has 5 states:
     * -Default State
     *   -Started State
     *     -Hw Pno Scan state
     *       -Single Scan state
     *
     * These are the main state transitions:
     * 1. Start at |Default State|
     * 2. Move to |Started State| when we get the |WIFI_SCAN_AVAILABLE| broadcast from WifiManager.
     * 3. When a new PNO scan request comes in:
     *   a.1. Switch to |Hw Pno Scan state| when the device supports HW PNO
     *        (This could either be HAL based ePNO or wificond based PNO).
     *   a.2. In |Hw Pno Scan state| when PNO scan results are received, check if the result
     *        contains IE (information elements). If yes, send the results to the client, else
     *        switch to |Single Scan state| and send the result to the client when the scan result
     *        is obtained.
     *
     * Note: PNO scans only work for a single client today. We don't have support in HW to support
     * multiple requests at the same time, so will need non-trivial changes to support (if at all
     * possible) in WifiScanningService.
     */
    class WifiPnoScanStateMachine extends StateMachine implements WifiNative.PnoEventHandler {

        private final DefaultState mDefaultState = new DefaultState();
        private final StartedState mStartedState = new StartedState();
        private final HwPnoScanState mHwPnoScanState = new HwPnoScanState();
        private final SingleScanState mSingleScanState = new SingleScanState();
        private InternalClientInfo mInternalClientInfo;

        private final RequestList<Pair<PnoSettings, ScanSettings>> mActivePnoScans =
                new RequestList<>();

        WifiPnoScanStateMachine(Looper looper) {
            super("WifiPnoScanStateMachine", looper);

            setLogRecSize(256);
            setLogOnlyTransitions(false);

            // CHECKSTYLE:OFF IndentationCheck
            addState(mDefaultState);
                addState(mStartedState, mDefaultState);
                    addState(mHwPnoScanState, mStartedState);
                        addState(mSingleScanState, mHwPnoScanState);
            // CHECKSTYLE:ON IndentationCheck

            setInitialState(mDefaultState);
        }

        public void removePnoSettings(ClientInfo ci) {
            mActivePnoScans.removeAllForClient(ci);
            transitionTo(mStartedState);
        }

        @Override
        public void onPnoNetworkFound(ScanResult[] results) {
            if (DBG) localLog("onWifiPnoNetworkFound event received");
            sendMessage(CMD_PNO_NETWORK_FOUND, 0, 0, results);
        }

        @Override
        public void onPnoScanFailed() {
            if (DBG) localLog("onWifiPnoScanFailed event received");
            sendMessage(CMD_PNO_SCAN_FAILED, 0, 0, null);
        }

        class DefaultState extends State {
            @Override
            public void enter() {
                if (DBG) localLog("DefaultState");
            }

            @Override
            public boolean processMessage(Message msg) {
                switch (msg.what) {
                    case CMD_DRIVER_LOADED:
                        if (mScannerImpl == null) {
                            loge("Failed to start pno scan state machine because scanner impl"
                                    + " is null");
                            return HANDLED;
                        }
                        transitionTo(mStartedState);
                        break;
                    case CMD_DRIVER_UNLOADED:
                        transitionTo(mDefaultState);
                        break;
                    case WifiScanner.CMD_START_PNO_SCAN:
                    case WifiScanner.CMD_STOP_PNO_SCAN:
                        replyFailed(msg, WifiScanner.REASON_UNSPECIFIED, "not available");
                        break;
                    case CMD_PNO_NETWORK_FOUND:
                    case CMD_PNO_SCAN_FAILED:
                    case WifiScanner.CMD_SCAN_RESULT:
                    case WifiScanner.CMD_OP_FAILED:
                        loge("Unexpected message " + msg.what);
                        break;
                    default:
                        return NOT_HANDLED;
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
            public void exit() {
                sendPnoScanFailedToAllAndClear(
                        WifiScanner.REASON_UNSPECIFIED, "Scan was interrupted");
            }

            @Override
            public boolean processMessage(Message msg) {
                ClientInfo ci = mClients.get(msg.replyTo);
                switch (msg.what) {
                    case CMD_DRIVER_LOADED:
                        // Ignore if we're already in driver loaded state.
                        return HANDLED;
                    case WifiScanner.CMD_START_PNO_SCAN:
                        Bundle pnoParams = (Bundle) msg.obj;
                        if (pnoParams == null) {
                            replyFailed(msg, WifiScanner.REASON_INVALID_REQUEST, "params null");
                            return HANDLED;
                        }
                        pnoParams.setDefusable(true);
                        PnoSettings pnoSettings =
                                pnoParams.getParcelable(WifiScanner.PNO_PARAMS_PNO_SETTINGS_KEY);
                        if (mScannerImpl.isHwPnoSupported(pnoSettings.isConnected)) {
                            deferMessage(msg);
                            transitionTo(mHwPnoScanState);
                        } else {
                            replyFailed(msg, WifiScanner.REASON_INVALID_REQUEST, "not supported");
                        }
                        break;
                    case WifiScanner.CMD_STOP_PNO_SCAN:
                        replyFailed(msg, WifiScanner.REASON_UNSPECIFIED, "no scan running");
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class HwPnoScanState extends State {
            @Override
            public void enter() {
                if (DBG) localLog("HwPnoScanState");
            }

            @Override
            public void exit() {
                // Reset PNO scan in ScannerImpl before we exit.
                mScannerImpl.resetHwPnoList();
                removeInternalClient();
            }

            @Override
            public boolean processMessage(Message msg) {
                ClientInfo ci = mClients.get(msg.replyTo);
                switch (msg.what) {
                    case WifiScanner.CMD_START_PNO_SCAN:
                        Bundle pnoParams = (Bundle) msg.obj;
                        if (pnoParams == null) {
                            replyFailed(msg, WifiScanner.REASON_INVALID_REQUEST, "params null");
                            return HANDLED;
                        }
                        pnoParams.setDefusable(true);
                        PnoSettings pnoSettings =
                                pnoParams.getParcelable(WifiScanner.PNO_PARAMS_PNO_SETTINGS_KEY);
                        ScanSettings scanSettings =
                                pnoParams.getParcelable(WifiScanner.PNO_PARAMS_SCAN_SETTINGS_KEY);
                        if (addHwPnoScanRequest(ci, msg.arg2, scanSettings, pnoSettings)) {
                            replySucceeded(msg);
                        } else {
                            replyFailed(msg, WifiScanner.REASON_INVALID_REQUEST, "bad request");
                            transitionTo(mStartedState);
                        }
                        break;
                    case WifiScanner.CMD_STOP_PNO_SCAN:
                        removeHwPnoScanRequest(ci, msg.arg2);
                        transitionTo(mStartedState);
                        break;
                    case CMD_PNO_NETWORK_FOUND:
                        ScanResult[] scanResults = ((ScanResult[]) msg.obj);
                        if (isSingleScanNeeded(scanResults)) {
                            ScanSettings activeScanSettings = getScanSettings();
                            if (activeScanSettings == null) {
                                sendPnoScanFailedToAllAndClear(
                                        WifiScanner.REASON_UNSPECIFIED,
                                        "couldn't retrieve setting");
                                transitionTo(mStartedState);
                            } else {
                                addSingleScanRequest(activeScanSettings);
                                transitionTo(mSingleScanState);
                            }
                        } else {
                            reportPnoNetworkFound((ScanResult[]) msg.obj);
                        }
                        break;
                    case CMD_PNO_SCAN_FAILED:
                        sendPnoScanFailedToAllAndClear(
                                WifiScanner.REASON_UNSPECIFIED, "pno scan failed");
                        transitionTo(mStartedState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class SingleScanState extends State {
            @Override
            public void enter() {
                if (DBG) localLog("SingleScanState");
            }

            @Override
            public boolean processMessage(Message msg) {
                ClientInfo ci = mClients.get(msg.replyTo);
                switch (msg.what) {
                    case WifiScanner.CMD_SCAN_RESULT:
                        WifiScanner.ParcelableScanData parcelableScanData =
                                (WifiScanner.ParcelableScanData) msg.obj;
                        ScanData[] scanDatas = parcelableScanData.getResults();
                        ScanData lastScanData = scanDatas[scanDatas.length - 1];
                        reportPnoNetworkFound(lastScanData.getResults());
                        transitionTo(mHwPnoScanState);
                        break;
                    case WifiScanner.CMD_OP_FAILED:
                        sendPnoScanFailedToAllAndClear(
                                WifiScanner.REASON_UNSPECIFIED, "single scan failed");
                        transitionTo(mStartedState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        private WifiNative.PnoSettings convertSettingsToPnoNative(ScanSettings scanSettings,
                                                                  PnoSettings pnoSettings) {
            WifiNative.PnoSettings nativePnoSetting = new WifiNative.PnoSettings();
            nativePnoSetting.periodInMs = scanSettings.periodInMs;
            nativePnoSetting.min5GHzRssi = pnoSettings.min5GHzRssi;
            nativePnoSetting.min24GHzRssi = pnoSettings.min24GHzRssi;
            nativePnoSetting.initialScoreMax = pnoSettings.initialScoreMax;
            nativePnoSetting.currentConnectionBonus = pnoSettings.currentConnectionBonus;
            nativePnoSetting.sameNetworkBonus = pnoSettings.sameNetworkBonus;
            nativePnoSetting.secureBonus = pnoSettings.secureBonus;
            nativePnoSetting.band5GHzBonus = pnoSettings.band5GHzBonus;
            nativePnoSetting.isConnected = pnoSettings.isConnected;
            nativePnoSetting.networkList =
                    new WifiNative.PnoNetwork[pnoSettings.networkList.length];
            for (int i = 0; i < pnoSettings.networkList.length; i++) {
                nativePnoSetting.networkList[i] = new WifiNative.PnoNetwork();
                nativePnoSetting.networkList[i].ssid = pnoSettings.networkList[i].ssid;
                nativePnoSetting.networkList[i].flags = pnoSettings.networkList[i].flags;
                nativePnoSetting.networkList[i].auth_bit_field =
                        pnoSettings.networkList[i].authBitField;
                nativePnoSetting.networkList[i].frequencies =
                        pnoSettings.networkList[i].frequencies;
            }
            return nativePnoSetting;
        }

        // Retrieve the only active scan settings.
        private ScanSettings getScanSettings() {
            for (Pair<PnoSettings, ScanSettings> settingsPair : mActivePnoScans.getAllSettings()) {
                return settingsPair.second;
            }
            return null;
        }

        private void removeInternalClient() {
            if (mInternalClientInfo != null) {
                mInternalClientInfo.cleanup();
                mInternalClientInfo = null;
            } else {
                Log.w(TAG, "No Internal client for PNO");
            }
        }

        private void addInternalClient(ClientInfo ci) {
            if (mInternalClientInfo == null) {
                mInternalClientInfo =
                        new InternalClientInfo(ci.getUid(), new Messenger(this.getHandler()));
                mInternalClientInfo.register();
            } else {
                Log.w(TAG, "Internal client for PNO already exists");
            }
        }

        private void addPnoScanRequest(ClientInfo ci, int handler, ScanSettings scanSettings,
                PnoSettings pnoSettings) {
            mActivePnoScans.addRequest(ci, handler, ClientModeImpl.WIFI_WORK_SOURCE,
                    Pair.create(pnoSettings, scanSettings));
            addInternalClient(ci);
        }

        private Pair<PnoSettings, ScanSettings> removePnoScanRequest(ClientInfo ci, int handler) {
            Pair<PnoSettings, ScanSettings> settings = mActivePnoScans.removeRequest(ci, handler);
            return settings;
        }

        private boolean addHwPnoScanRequest(ClientInfo ci, int handler, ScanSettings scanSettings,
                PnoSettings pnoSettings) {
            if (ci == null) {
                Log.d(TAG, "Failing scan request ClientInfo not found " + handler);
                return false;
            }
            if (!mActivePnoScans.isEmpty()) {
                loge("Failing scan request because there is already an active scan");
                return false;
            }
            WifiNative.PnoSettings nativePnoSettings =
                    convertSettingsToPnoNative(scanSettings, pnoSettings);
            if (!mScannerImpl.setHwPnoList(nativePnoSettings, mPnoScanStateMachine)) {
                return false;
            }
            logScanRequest("addHwPnoScanRequest", ci, handler, null, scanSettings, pnoSettings);
            addPnoScanRequest(ci, handler, scanSettings, pnoSettings);

            return true;
        }

        private void removeHwPnoScanRequest(ClientInfo ci, int handler) {
            if (ci != null) {
                Pair<PnoSettings, ScanSettings> settings = removePnoScanRequest(ci, handler);
                logScanRequest("removeHwPnoScanRequest", ci, handler, null,
                        settings.second, settings.first);
            }
        }

        private void reportPnoNetworkFound(ScanResult[] results) {
            WifiScanner.ParcelableScanResults parcelableScanResults =
                    new WifiScanner.ParcelableScanResults(results);
            for (RequestInfo<Pair<PnoSettings, ScanSettings>> entry : mActivePnoScans) {
                ClientInfo ci = entry.clientInfo;
                int handler = entry.handlerId;
                logCallback("pnoNetworkFound", ci, handler, describeForLog(results));
                ci.reportEvent(
                        WifiScanner.CMD_PNO_NETWORK_FOUND, 0, handler, parcelableScanResults);
            }
        }

        private void sendPnoScanFailedToAllAndClear(int reason, String description) {
            for (RequestInfo<Pair<PnoSettings, ScanSettings>> entry : mActivePnoScans) {
                ClientInfo ci = entry.clientInfo;
                int handler = entry.handlerId;
                ci.reportEvent(WifiScanner.CMD_OP_FAILED, 0, handler,
                        new WifiScanner.OperationResult(reason, description));
            }
            mActivePnoScans.clear();
        }

        private void addSingleScanRequest(ScanSettings settings) {
            if (DBG) localLog("Starting single scan");
            if (mInternalClientInfo != null) {
                mInternalClientInfo.sendRequestToClientHandler(
                        WifiScanner.CMD_START_SINGLE_SCAN, settings,
                        ClientModeImpl.WIFI_WORK_SOURCE);
            }
        }

        /**
         * Checks if IE are present in scan data, if no single scan is needed to report event to
         * client
         */
        private boolean isSingleScanNeeded(ScanResult[] scanResults) {
            for (ScanResult scanResult : scanResults) {
                if (scanResult.informationElements != null
                        && scanResult.informationElements.length > 0) {
                    return false;
                }
            }
            return true;
        }
    }

    private abstract class ClientInfo {
        private final int mUid;
        private final WorkSource mWorkSource;
        private boolean mScanWorkReported = false;
        protected final Messenger mMessenger;

        ClientInfo(int uid, Messenger messenger) {
            mUid = uid;
            mMessenger = messenger;
            mWorkSource = new WorkSource(uid);
        }

        /**
         * Register this client to main client map.
         */
        public void register() {
            mClients.put(mMessenger, this);
        }

        /**
         * Unregister this client from main client map.
         */
        private void unregister() {
            mClients.remove(mMessenger);
        }

        public void cleanup() {
            mSingleScanListeners.removeAllForClient(this);
            mSingleScanStateMachine.removeSingleScanRequests(this);
            mBackgroundScanStateMachine.removeBackgroundScanSettings(this);
            unregister();
            localLog("Successfully stopped all requests for client " + this);
        }

        public int getUid() {
            return mUid;
        }

        public void reportEvent(int what, int arg1, int arg2) {
            reportEvent(what, arg1, arg2, null);
        }

        // This has to be implemented by subclasses to report events back to clients.
        public abstract void reportEvent(int what, int arg1, int arg2, Object obj);

        // TODO(b/27903217, 71530998): This is dead code. Should this be wired up ?
        private void reportBatchedScanStart() {
            if (mUid == 0)
                return;

            int csph = getCsph();

            try {
                mBatteryStats.noteWifiBatchedScanStartedFromSource(mWorkSource, csph);
            } catch (RemoteException e) {
                logw("failed to report scan work: " + e.toString());
            }
        }

        // TODO(b/27903217, 71530998): This is dead code. Should this be wired up ?
        private void reportBatchedScanStop() {
            if (mUid == 0)
                return;

            try {
                mBatteryStats.noteWifiBatchedScanStoppedFromSource(mWorkSource);
            } catch (RemoteException e) {
                logw("failed to cleanup scan work: " + e.toString());
            }
        }

        // TODO migrate batterystats to accept scan duration per hour instead of csph
        private int getCsph() {
            int totalScanDurationPerHour = 0;
            Collection<ScanSettings> settingsList =
                    mBackgroundScanStateMachine.getBackgroundScanSettings(this);
            for (ScanSettings settings : settingsList) {
                int scanDurationMs = mChannelHelper.estimateScanDuration(settings);
                int scans_per_Hour = settings.periodInMs == 0 ? 1 : (3600 * 1000) /
                        settings.periodInMs;
                totalScanDurationPerHour += scanDurationMs * scans_per_Hour;
            }

            return totalScanDurationPerHour / ChannelHelper.SCAN_PERIOD_PER_CHANNEL_MS;
        }

        // TODO(b/27903217, 71530998): This is dead code. Should this be wired up ?
        private void reportScanWorkUpdate() {
            if (mScanWorkReported) {
                reportBatchedScanStop();
                mScanWorkReported = false;
            }
            if (mBackgroundScanStateMachine.getBackgroundScanSettings(this).isEmpty()) {
                reportBatchedScanStart();
                mScanWorkReported = true;
            }
        }

        @Override
        public String toString() {
            return "ClientInfo[uid=" + mUid + "," + mMessenger + "]";
        }
    }

    /**
     * This class is used to represent external clients to the WifiScanning Service.
     */
    private class ExternalClientInfo extends ClientInfo {
        private final AsyncChannel mChannel;
        /**
         * Indicates if the client is still connected
         * If the client is no longer connected then messages to it will be silently dropped
         */
        private boolean mDisconnected = false;

        ExternalClientInfo(int uid, Messenger messenger, AsyncChannel c) {
            super(uid, messenger);
            mChannel = c;
            if (DBG) localLog("New client, channel: " + c);
        }

        @Override
        public void reportEvent(int what, int arg1, int arg2, Object obj) {
            if (!mDisconnected) {
                mChannel.sendMessage(what, arg1, arg2, obj);
            }
        }

        @Override
        public void cleanup() {
            mDisconnected = true;
            mPnoScanStateMachine.removePnoSettings(this);
            super.cleanup();
        }
    }

    /**
     * This class is used to represent internal clients to the WifiScanning Service. This is needed
     * for communicating between State Machines.
     * This leaves the onReportEvent method unimplemented, so that the clients have the freedom
     * to handle the events as they need.
     */
    private class InternalClientInfo extends ClientInfo {
        private static final int INTERNAL_CLIENT_HANDLER = 0;

        /**
         * The UID here is used to proxy the original external requester UID.
         */
        InternalClientInfo(int requesterUid, Messenger messenger) {
            super(requesterUid, messenger);
        }

        @Override
        public void reportEvent(int what, int arg1, int arg2, Object obj) {
            Message message = Message.obtain();
            message.what = what;
            message.arg1 = arg1;
            message.arg2 = arg2;
            message.obj = obj;
            try {
                mMessenger.send(message);
            } catch (RemoteException e) {
                loge("Failed to send message: " + what);
            }
        }

        /**
         * Send a message to the client handler which should reroute the message to the appropriate
         * state machine.
         */
        public void sendRequestToClientHandler(int what, ScanSettings settings,
                WorkSource workSource) {
            Message msg = Message.obtain();
            msg.what = what;
            msg.arg2 = INTERNAL_CLIENT_HANDLER;
            if (settings != null) {
                Bundle bundle = new Bundle();
                bundle.putParcelable(WifiScanner.SCAN_PARAMS_SCAN_SETTINGS_KEY, settings);
                bundle.putParcelable(WifiScanner.SCAN_PARAMS_WORK_SOURCE_KEY, workSource);
                msg.obj = bundle;
            }
            msg.replyTo = mMessenger;
            msg.sendingUid = getUid();
            mClientHandler.sendMessage(msg);
        }

        /**
         * Send a message to the client handler which should reroute the message to the appropriate
         * state machine.
         */
        public void sendRequestToClientHandler(int what) {
            sendRequestToClientHandler(what, null, null);
        }

        @Override
        public String toString() {
            return "InternalClientInfo[]";
        }
    }

    void replySucceeded(Message msg) {
        if (msg.replyTo != null) {
            Message reply = Message.obtain();
            reply.what = WifiScanner.CMD_OP_SUCCEEDED;
            reply.arg2 = msg.arg2;
            if (msg.obj != null) {
                reply.obj = msg.obj;
            }
            try {
                msg.replyTo.send(reply);
                mLog.trace("replySucceeded recvdMessage=%").c(msg.what).flush();
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
                mLog.trace("replyFailed recvdMessage=% reason=%")
                            .c(msg.what)
                            .c(reason)
                            .flush();
            } catch (RemoteException e) {
                // There's not much we can do if reply can't be sent!
            }
        } else {
            // locally generated message; doesn't need a reply!
        }
    }

    private static String toString(int uid, ScanSettings settings) {
        StringBuilder sb = new StringBuilder();
        sb.append("ScanSettings[uid=").append(uid);
        sb.append(", period=").append(settings.periodInMs);
        sb.append(", report=").append(settings.reportEvents);
        if (settings.reportEvents == WifiScanner.REPORT_EVENT_AFTER_BUFFER_FULL
                && settings.numBssidsPerScan > 0
                && settings.maxScansToCache > 1) {
            sb.append(", batch=").append(settings.maxScansToCache);
            sb.append(", numAP=").append(settings.numBssidsPerScan);
        }
        sb.append(", ").append(ChannelHelper.toString(settings));
        sb.append("]");

        return sb.toString();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump WifiScanner from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " without permission "
                    + android.Manifest.permission.DUMP);
            return;
        }
        pw.println("WifiScanningService - Log Begin ----");
        mLocalLog.dump(fd, pw, args);
        pw.println("WifiScanningService - Log End ----");
        pw.println();
        pw.println("clients:");
        for (ClientInfo client : mClients.values()) {
            pw.println("  " + client);
        }
        pw.println("listeners:");
        for (ClientInfo client : mClients.values()) {
            Collection<ScanSettings> settingsList =
                    mBackgroundScanStateMachine.getBackgroundScanSettings(client);
            for (ScanSettings settings : settingsList) {
                pw.println("  " + toString(client.mUid, settings));
            }
        }
        if (mBackgroundScheduler != null) {
            WifiNative.ScanSettings schedule = mBackgroundScheduler.getSchedule();
            if (schedule != null) {
                pw.println("schedule:");
                pw.println("  base period: " + schedule.base_period_ms);
                pw.println("  max ap per scan: " + schedule.max_ap_per_scan);
                pw.println("  batched scans: " + schedule.report_threshold_num_scans);
                pw.println("  buckets:");
                for (int b = 0; b < schedule.num_buckets; b++) {
                    WifiNative.BucketSettings bucket = schedule.buckets[b];
                    pw.println("    bucket " + bucket.bucket + " (" + bucket.period_ms + "ms)["
                            + bucket.report_events + "]: "
                            + ChannelHelper.toString(bucket));
                }
            }
        }
        if (mPnoScanStateMachine != null) {
            mPnoScanStateMachine.dump(fd, pw, args);
        }
        pw.println();

        if (mSingleScanStateMachine != null) {
            mSingleScanStateMachine.dump(fd, pw, args);
            pw.println();
            pw.println("Latest scan results:");
            List<ScanResult> scanResults = mSingleScanStateMachine.getCachedScanResultsAsList();
            long nowMs = mClock.getElapsedSinceBootMillis();
            ScanResultUtil.dumpScanResults(pw, scanResults, nowMs);
            pw.println();
        }
        if (mScannerImpl != null) {
            mScannerImpl.dump(fd, pw, args);
        }
    }

    void logScanRequest(String request, ClientInfo ci, int id, WorkSource workSource,
            ScanSettings settings, PnoSettings pnoSettings) {
        StringBuilder sb = new StringBuilder();
        sb.append(request)
                .append(": ")
                .append((ci == null) ? "ClientInfo[unknown]" : ci.toString())
                .append(",Id=")
                .append(id);
        if (workSource != null) {
            sb.append(",").append(workSource);
        }
        if (settings != null) {
            sb.append(", ");
            describeTo(sb, settings);
        }
        if (pnoSettings != null) {
            sb.append(", ");
            describeTo(sb, pnoSettings);
        }
        localLog(sb.toString());
    }

    void logCallback(String callback, ClientInfo ci, int id, String extra) {
        StringBuilder sb = new StringBuilder();
        sb.append(callback)
                .append(": ")
                .append((ci == null) ? "ClientInfo[unknown]" : ci.toString())
                .append(",Id=")
                .append(id);
        if (extra != null) {
            sb.append(",").append(extra);
        }
        localLog(sb.toString());
    }

    static String describeForLog(ScanData[] results) {
        StringBuilder sb = new StringBuilder();
        sb.append("results=");
        for (int i = 0; i < results.length; ++i) {
            if (i > 0) sb.append(";");
            sb.append(results[i].getResults().length);
        }
        return sb.toString();
    }

    static String describeForLog(ScanResult[] results) {
        return "results=" + results.length;
    }

    static String getScanTypeString(int type) {
        switch(type) {
            case WifiScanner.TYPE_LOW_LATENCY:
                return "LOW LATENCY";
            case WifiScanner.TYPE_LOW_POWER:
                return "LOW POWER";
            case WifiScanner.TYPE_HIGH_ACCURACY:
                return "HIGH ACCURACY";
            default:
                // This should never happen becuase we've validated the incoming type in
                // |validateScanType|.
                throw new IllegalArgumentException("Invalid scan type " + type);
        }
    }

    static String describeTo(StringBuilder sb, ScanSettings scanSettings) {
        sb.append("ScanSettings { ")
          .append(" type:").append(getScanTypeString(scanSettings.type))
          .append(" band:").append(ChannelHelper.bandToString(scanSettings.band))
          .append(" ignoreLocationSettings:").append(scanSettings.ignoreLocationSettings)
          .append(" period:").append(scanSettings.periodInMs)
          .append(" reportEvents:").append(scanSettings.reportEvents)
          .append(" numBssidsPerScan:").append(scanSettings.numBssidsPerScan)
          .append(" maxScansToCache:").append(scanSettings.maxScansToCache)
          .append(" channels:[ ");
        if (scanSettings.channels != null) {
            for (int i = 0; i < scanSettings.channels.length; i++) {
                sb.append(scanSettings.channels[i].frequency)
                  .append(" ");
            }
        }
        sb.append(" ] ")
          .append(" } ");
        return sb.toString();
    }

    static String describeTo(StringBuilder sb, PnoSettings pnoSettings) {
        sb.append("PnoSettings { ")
          .append(" min5GhzRssi:").append(pnoSettings.min5GHzRssi)
          .append(" min24GhzRssi:").append(pnoSettings.min24GHzRssi)
          .append(" initialScoreMax:").append(pnoSettings.initialScoreMax)
          .append(" currentConnectionBonus:").append(pnoSettings.currentConnectionBonus)
          .append(" sameNetworkBonus:").append(pnoSettings.sameNetworkBonus)
          .append(" secureBonus:").append(pnoSettings.secureBonus)
          .append(" band5GhzBonus:").append(pnoSettings.band5GHzBonus)
          .append(" isConnected:").append(pnoSettings.isConnected)
          .append(" networks:[ ");
        if (pnoSettings.networkList != null) {
            for (int i = 0; i < pnoSettings.networkList.length; i++) {
                sb.append(pnoSettings.networkList[i].ssid).append(",");
            }
        }
        sb.append(" ] ")
          .append(" } ");
        return sb.toString();
    }
}
