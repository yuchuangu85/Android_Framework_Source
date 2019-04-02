/*
 * Copyright (C) 2010 The Android Open Source Project
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

import static android.net.wifi.WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_FAILURE_REASON;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_INTERFACE_NAME;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_MODE;
import static android.net.wifi.WifiManager.EXTRA_WIFI_AP_STATE;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC;
import static android.net.wifi.WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL;
import static android.net.wifi.WifiManager.SAP_START_FAILURE_NO_CHANNEL;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLED;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_DISABLING;
import static android.net.wifi.WifiManager.WIFI_AP_STATE_FAILED;

import static com.android.server.wifi.LocalOnlyHotspotRequestInfo.HOTSPOT_NO_ERROR;
import static com.android.server.wifi.WifiController.CMD_AIRPLANE_TOGGLED;
import static com.android.server.wifi.WifiController.CMD_BATTERY_CHANGED;
import static com.android.server.wifi.WifiController.CMD_EMERGENCY_CALL_STATE_CHANGED;
import static com.android.server.wifi.WifiController.CMD_EMERGENCY_MODE_CHANGED;
import static com.android.server.wifi.WifiController.CMD_LOCKS_CHANGED;
import static com.android.server.wifi.WifiController.CMD_SCAN_ALWAYS_MODE_CHANGED;
import static com.android.server.wifi.WifiController.CMD_SCREEN_OFF;
import static com.android.server.wifi.WifiController.CMD_SCREEN_ON;
import static com.android.server.wifi.WifiController.CMD_SET_AP;
import static com.android.server.wifi.WifiController.CMD_USER_PRESENT;
import static com.android.server.wifi.WifiController.CMD_WIFI_TOGGLED;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.AppOpsManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.database.ContentObserver;
import android.net.DhcpInfo;
import android.net.DhcpResults;
import android.net.IpConfiguration;
import android.net.Network;
import android.net.NetworkUtils;
import android.net.StaticIpConfiguration;
import android.net.Uri;
import android.net.ip.IpManager;
import android.net.wifi.IWifiManager;
import android.net.wifi.ScanResult;
import android.net.wifi.ScanSettings;
import android.net.wifi.WifiActivityEnergyInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConnectionStatistics;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiLinkLayerStats;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback;
import android.net.wifi.WifiScanner;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.os.AsyncTask;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.AsyncChannel;
import com.android.server.wifi.hotspot2.PasspointProvider;
import com.android.server.wifi.util.WifiHandler;
import com.android.server.wifi.util.WifiPermissionsUtil;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WifiService handles remote WiFi operation requests by implementing
 * the IWifiManager interface.
 *
 * @hide
 */
public class WifiServiceImpl extends IWifiManager.Stub {
    private static final String TAG = "WifiService";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    // Dumpsys argument to enable/disable disconnect on IP reachability failures.
    private static final String DUMP_ARG_SET_IPREACH_DISCONNECT = "set-ipreach-disconnect";
    private static final String DUMP_ARG_SET_IPREACH_DISCONNECT_ENABLED = "enabled";
    private static final String DUMP_ARG_SET_IPREACH_DISCONNECT_DISABLED = "disabled";

    // Default scan background throttling interval if not overriden in settings
    private static final long DEFAULT_SCAN_BACKGROUND_THROTTLE_INTERVAL_MS = 30 * 60 * 1000;

    // Apps with importance higher than this value is considered as background app.
    private static final int BACKGROUND_IMPORTANCE_CUTOFF =
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;

    final WifiStateMachine mWifiStateMachine;

    private final Context mContext;
    private final FrameworkFacade mFacade;
    private final Clock mClock;
    private final ArraySet<String> mBackgroundThrottlePackageWhitelist = new ArraySet<>();

    private final PowerManager mPowerManager;
    private final AppOpsManager mAppOps;
    private final UserManager mUserManager;
    private final ActivityManager mActivityManager;
    private final WifiCountryCode mCountryCode;
    private long mBackgroundThrottleInterval;
    // Debug counter tracking scan requests sent by WifiManager
    private int scanRequestCounter = 0;

    /* Polls traffic stats and notifies clients */
    private WifiTrafficPoller mTrafficPoller;
    /* Tracks the persisted states for wi-fi & airplane mode */
    final WifiSettingsStore mSettingsStore;
    /* Logs connection events and some general router and scan stats */
    private final WifiMetrics mWifiMetrics;
    /* Manages affiliated certificates for current user */
    private final WifiCertManager mCertManager;

    private final WifiInjector mWifiInjector;
    /* Backup/Restore Module */
    private final WifiBackupRestore mWifiBackupRestore;

    // Map of package name of background scan apps and last scan timestamp.
    private final ArrayMap<String, Long> mLastScanTimestamps;

    private WifiScanner mWifiScanner;
    private WifiLog mLog;

    /**
     * Asynchronous channel to WifiStateMachine
     */
    private AsyncChannel mWifiStateMachineChannel;

    private final boolean mPermissionReviewRequired;
    private final FrameworkFacade mFrameworkFacade;

    private WifiPermissionsUtil mWifiPermissionsUtil;

    @GuardedBy("mLocalOnlyHotspotRequests")
    private final HashMap<Integer, LocalOnlyHotspotRequestInfo> mLocalOnlyHotspotRequests;
    @GuardedBy("mLocalOnlyHotspotRequests")
    private WifiConfiguration mLocalOnlyHotspotConfig = null;
    @GuardedBy("mLocalOnlyHotspotRequests")
    private final ConcurrentHashMap<String, Integer> mIfaceIpModes;

    /**
     * Callback for use with LocalOnlyHotspot to unregister requesting applications upon death.
     *
     * @hide
     */
    public final class LocalOnlyRequestorCallback
            implements LocalOnlyHotspotRequestInfo.RequestingApplicationDeathCallback {
        /**
         * Called with requesting app has died.
         */
        @Override
        public void onLocalOnlyHotspotRequestorDeath(LocalOnlyHotspotRequestInfo requestor) {
            unregisterCallingAppAndStopLocalOnlyHotspot(requestor);
        };
    }

    /**
     * Handles client connections
     */
    private class ClientHandler extends WifiHandler {

        ClientHandler(String tag, Looper looper) {
            super(tag, looper);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED: {
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        if (DBG) Slog.d(TAG, "New client listening to asynchronous messages");
                        // We track the clients by the Messenger
                        // since it is expected to be always available
                        mTrafficPoller.addClient(msg.replyTo);
                    } else {
                        Slog.e(TAG, "Client connection failure, error=" + msg.arg1);
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    if (msg.arg1 == AsyncChannel.STATUS_SEND_UNSUCCESSFUL) {
                        if (DBG) Slog.d(TAG, "Send failed, client connection lost");
                    } else {
                        if (DBG) Slog.d(TAG, "Client connection lost with reason: " + msg.arg1);
                    }
                    mTrafficPoller.removeClient(msg.replyTo);
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                    AsyncChannel ac = mFrameworkFacade.makeWifiAsyncChannel(TAG);
                    ac.connect(mContext, this, msg.replyTo);
                    break;
                }
                case WifiManager.CONNECT_NETWORK: {
                    if (checkChangePermissionAndReplyIfNotAuthorized(
                            msg, WifiManager.CONNECT_NETWORK_FAILED)) {
                        WifiConfiguration config = (WifiConfiguration) msg.obj;
                        int networkId = msg.arg1;
                        Slog.d(TAG, "CONNECT "
                                + " nid=" + Integer.toString(networkId)
                                + " uid=" + msg.sendingUid
                                + " name="
                                + mContext.getPackageManager().getNameForUid(msg.sendingUid));
                        if (config != null) {
                            if (DBG) Slog.d(TAG, "Connect with config " + config);
                            /* Command is forwarded to state machine */
                            mWifiStateMachine.sendMessage(Message.obtain(msg));
                        } else if (config == null
                                && networkId != WifiConfiguration.INVALID_NETWORK_ID) {
                            if (DBG) Slog.d(TAG, "Connect with networkId " + networkId);
                            mWifiStateMachine.sendMessage(Message.obtain(msg));
                        } else {
                            Slog.e(TAG, "ClientHandler.handleMessage ignoring invalid msg=" + msg);
                            replyFailed(msg, WifiManager.CONNECT_NETWORK_FAILED,
                                    WifiManager.INVALID_ARGS);
                        }
                    }
                    break;
                }
                case WifiManager.SAVE_NETWORK: {
                    if (checkChangePermissionAndReplyIfNotAuthorized(
                            msg, WifiManager.SAVE_NETWORK_FAILED)) {
                        WifiConfiguration config = (WifiConfiguration) msg.obj;
                        int networkId = msg.arg1;
                        Slog.d(TAG, "SAVE"
                                + " nid=" + Integer.toString(networkId)
                                + " uid=" + msg.sendingUid
                                + " name="
                                + mContext.getPackageManager().getNameForUid(msg.sendingUid));
                        if (config != null) {
                            if (DBG) Slog.d(TAG, "Save network with config " + config);
                            /* Command is forwarded to state machine */
                            mWifiStateMachine.sendMessage(Message.obtain(msg));
                        } else {
                            Slog.e(TAG, "ClientHandler.handleMessage ignoring invalid msg=" + msg);
                            replyFailed(msg, WifiManager.SAVE_NETWORK_FAILED,
                                    WifiManager.INVALID_ARGS);
                        }
                    }
                    break;
                }
                case WifiManager.FORGET_NETWORK:
                    if (checkChangePermissionAndReplyIfNotAuthorized(
                            msg, WifiManager.FORGET_NETWORK_FAILED)) {
                        mWifiStateMachine.sendMessage(Message.obtain(msg));
                    }
                    break;
                case WifiManager.START_WPS:
                    if (checkChangePermissionAndReplyIfNotAuthorized(msg, WifiManager.WPS_FAILED)) {
                        mWifiStateMachine.sendMessage(Message.obtain(msg));
                    }
                    break;
                case WifiManager.CANCEL_WPS:
                    if (checkChangePermissionAndReplyIfNotAuthorized(
                            msg, WifiManager.CANCEL_WPS_FAILED)) {
                        mWifiStateMachine.sendMessage(Message.obtain(msg));
                    }
                    break;
                case WifiManager.DISABLE_NETWORK:
                    if (checkChangePermissionAndReplyIfNotAuthorized(
                            msg, WifiManager.DISABLE_NETWORK_FAILED)) {
                        mWifiStateMachine.sendMessage(Message.obtain(msg));
                    }
                    break;
                case WifiManager.RSSI_PKTCNT_FETCH: {
                    if (checkChangePermissionAndReplyIfNotAuthorized(
                            msg, WifiManager.RSSI_PKTCNT_FETCH_FAILED)) {
                        mWifiStateMachine.sendMessage(Message.obtain(msg));
                    }
                    break;
                }
                default: {
                    Slog.d(TAG, "ClientHandler.handleMessage ignoring msg=" + msg);
                    break;
                }
            }
        }

        /**
         * Helper method to check if the sender of the message holds the
         * {@link Manifest.permission#CHANGE_WIFI_STATE} permission, and reply with a failure if it
         * doesn't
         *
         * @param msg Incoming message.
         * @param replyWhat Param to be filled in the {@link Message#what} field of the failure
         *                  reply.
         * @return true if the sender holds the permission, false otherwise.
         */
        private boolean checkChangePermissionAndReplyIfNotAuthorized(Message msg, int replyWhat) {
            if (!mWifiPermissionsUtil.checkChangePermission(msg.sendingUid)) {
                Slog.e(TAG, "ClientHandler.handleMessage ignoring unauthorized msg=" + msg);
                replyFailed(msg, replyWhat, WifiManager.NOT_AUTHORIZED);
                return false;
            }
            return true;
        }

        private void replyFailed(Message msg, int what, int why) {
            if (msg.replyTo == null) return;
            Message reply = Message.obtain();
            reply.what = what;
            reply.arg1 = why;
            try {
                msg.replyTo.send(reply);
            } catch (RemoteException e) {
                // There's not much we can do if reply can't be sent!
            }
        }
    }
    private ClientHandler mClientHandler;

    /**
     * Handles interaction with WifiStateMachine
     */
    private class WifiStateMachineHandler extends WifiHandler {
        private AsyncChannel mWsmChannel;

        WifiStateMachineHandler(String tag, Looper looper, AsyncChannel asyncChannel) {
            super(tag, looper);
            mWsmChannel = asyncChannel;
            mWsmChannel.connect(mContext, this, mWifiStateMachine.getHandler());
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED: {
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        mWifiStateMachineChannel = mWsmChannel;
                    } else {
                        Slog.e(TAG, "WifiStateMachine connection failure, error=" + msg.arg1);
                        mWifiStateMachineChannel = null;
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    Slog.e(TAG, "WifiStateMachine channel lost, msg.arg1 =" + msg.arg1);
                    mWifiStateMachineChannel = null;
                    //Re-establish connection to state machine
                    mWsmChannel.connect(mContext, this, mWifiStateMachine.getHandler());
                    break;
                }
                default: {
                    Slog.d(TAG, "WifiStateMachineHandler.handleMessage ignoring msg=" + msg);
                    break;
                }
            }
        }
    }

    WifiStateMachineHandler mWifiStateMachineHandler;
    private WifiController mWifiController;
    private final WifiLockManager mWifiLockManager;
    private final WifiMulticastLockManager mWifiMulticastLockManager;

    public WifiServiceImpl(Context context, WifiInjector wifiInjector, AsyncChannel asyncChannel) {
        mContext = context;
        mWifiInjector = wifiInjector;
        mClock = wifiInjector.getClock();

        mFacade = mWifiInjector.getFrameworkFacade();
        mWifiMetrics = mWifiInjector.getWifiMetrics();
        mTrafficPoller = mWifiInjector.getWifiTrafficPoller();
        mUserManager = mWifiInjector.getUserManager();
        mCountryCode = mWifiInjector.getWifiCountryCode();
        mWifiStateMachine = mWifiInjector.getWifiStateMachine();
        mWifiStateMachine.enableRssiPolling(true);
        mSettingsStore = mWifiInjector.getWifiSettingsStore();
        mPowerManager = mContext.getSystemService(PowerManager.class);
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mCertManager = mWifiInjector.getWifiCertManager();
        mWifiLockManager = mWifiInjector.getWifiLockManager();
        mWifiMulticastLockManager = mWifiInjector.getWifiMulticastLockManager();
        HandlerThread wifiServiceHandlerThread = mWifiInjector.getWifiServiceHandlerThread();
        mClientHandler = new ClientHandler(TAG, wifiServiceHandlerThread.getLooper());
        mWifiStateMachineHandler = new WifiStateMachineHandler(TAG,
                wifiServiceHandlerThread.getLooper(), asyncChannel);
        mWifiController = mWifiInjector.getWifiController();
        mWifiBackupRestore = mWifiInjector.getWifiBackupRestore();
        mPermissionReviewRequired = Build.PERMISSIONS_REVIEW_REQUIRED
                || context.getResources().getBoolean(
                com.android.internal.R.bool.config_permissionReviewRequired);
        mWifiPermissionsUtil = mWifiInjector.getWifiPermissionsUtil();
        mLog = mWifiInjector.makeLog(TAG);
        mFrameworkFacade = wifiInjector.getFrameworkFacade();
        mLastScanTimestamps = new ArrayMap<>();
        updateBackgroundThrottleInterval();
        updateBackgroundThrottlingWhitelist();
        mIfaceIpModes = new ConcurrentHashMap<>();
        mLocalOnlyHotspotRequests = new HashMap<>();
        enableVerboseLoggingInternal(getVerboseLoggingLevel());
    }

    /**
     * Provide a way for unit tests to set valid log object in the WifiHandler
     * @param log WifiLog object to assign to the clientHandler
     */
    @VisibleForTesting
    public void setWifiHandlerLogForTest(WifiLog log) {
        mClientHandler.setWifiLog(log);
    }

    /**
     * Check if we are ready to start wifi.
     *
     * First check if we will be restarting system services to decrypt the device. If the device is
     * not encrypted, check if Wi-Fi needs to be enabled and start if needed
     *
     * This function is used only at boot time.
     */
    public void checkAndStartWifi() {
        // First check if we will end up restarting WifiService
        if (mFrameworkFacade.inStorageManagerCryptKeeperBounce()) {
            Log.d(TAG, "Device still encrypted. Need to restart SystemServer.  Do not start wifi.");
            return;
        }

        // Check if wi-fi needs to be enabled
        boolean wifiEnabled = mSettingsStore.isWifiToggleEnabled();
        Slog.i(TAG, "WifiService starting up with Wi-Fi " +
                (wifiEnabled ? "enabled" : "disabled"));

        registerForScanModeChange();
        registerForBackgroundThrottleChanges();
        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (mSettingsStore.handleAirplaneModeToggled()) {
                            mWifiController.sendMessage(CMD_AIRPLANE_TOGGLED);
                        }
                        if (mSettingsStore.isAirplaneModeOn()) {
                            Log.d(TAG, "resetting country code because Airplane mode is ON");
                            mCountryCode.airplaneModeEnabled();
                        }
                    }
                },
                new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED));

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String state = intent.getStringExtra(IccCardConstants.INTENT_KEY_ICC_STATE);
                        if (IccCardConstants.INTENT_VALUE_ICC_ABSENT.equals(state)) {
                            Log.d(TAG, "resetting networks because SIM was removed");
                            mWifiStateMachine.resetSimAuthNetworks(false);
                            Log.d(TAG, "resetting country code because SIM is removed");
                            mCountryCode.simCardRemoved();
                        } else if (IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(state)) {
                            Log.d(TAG, "resetting networks because SIM was loaded");
                            mWifiStateMachine.resetSimAuthNetworks(true);
                        }
                    }
                },
                new IntentFilter(TelephonyIntents.ACTION_SIM_STATE_CHANGED));

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        final int currState = intent.getIntExtra(EXTRA_WIFI_AP_STATE,
                                                                    WIFI_AP_STATE_DISABLED);
                        final int prevState = intent.getIntExtra(EXTRA_PREVIOUS_WIFI_AP_STATE,
                                                                 WIFI_AP_STATE_DISABLED);
                        final int errorCode = intent.getIntExtra(EXTRA_WIFI_AP_FAILURE_REASON,
                                                                 HOTSPOT_NO_ERROR);
                        final String ifaceName =
                                intent.getStringExtra(EXTRA_WIFI_AP_INTERFACE_NAME);
                        final int mode = intent.getIntExtra(EXTRA_WIFI_AP_MODE,
                                                            WifiManager.IFACE_IP_MODE_UNSPECIFIED);
                        handleWifiApStateChange(currState, prevState, errorCode, ifaceName, mode);
                    }
                },
                new IntentFilter(WifiManager.WIFI_AP_STATE_CHANGED_ACTION));

        // Adding optimizations of only receiving broadcasts when wifi is enabled
        // can result in race conditions when apps toggle wifi in the background
        // without active user involvement. Always receive broadcasts.
        registerForBroadcasts();
        registerForPackageOrUserRemoval();
        mInIdleMode = mPowerManager.isDeviceIdleMode();

        if (!mWifiStateMachine.syncInitialize(mWifiStateMachineChannel)) {
            Log.wtf(TAG, "Failed to initialize WifiStateMachine");
        }
        mWifiController.start();

        // If we are already disabled (could be due to airplane mode), avoid changing persist
        // state here
        if (wifiEnabled) {
            try {
                setWifiEnabled(mContext.getPackageName(), wifiEnabled);
            } catch (RemoteException e) {
                /* ignore - local call */
            }
        }
    }

    public void handleUserSwitch(int userId) {
        mWifiStateMachine.handleUserSwitch(userId);
    }

    public void handleUserUnlock(int userId) {
        mWifiStateMachine.handleUserUnlock(userId);
    }

    public void handleUserStop(int userId) {
        mWifiStateMachine.handleUserStop(userId);
    }

    /**
     * see {@link android.net.wifi.WifiManager#startScan}
     * and {@link android.net.wifi.WifiManager#startCustomizedScan}
     *
     * @param settings If null, use default parameter, i.e. full scan.
     * @param workSource If null, all blame is given to the calling uid.
     * @param packageName Package name of the app that requests wifi scan.
     */
    @Override
    public void startScan(ScanSettings settings, WorkSource workSource, String packageName) {
        enforceChangePermission();

        mLog.info("startScan uid=%").c(Binder.getCallingUid()).flush();
        // Check and throttle background apps for wifi scan.
        if (isRequestFromBackground(packageName)) {
            long lastScanMs = mLastScanTimestamps.getOrDefault(packageName, 0L);
            long elapsedRealtime = mClock.getElapsedSinceBootMillis();

            if (lastScanMs != 0 && (elapsedRealtime - lastScanMs) < mBackgroundThrottleInterval) {
                sendFailedScanBroadcast();
                return;
            }
            // Proceed with the scan request and record the time.
            mLastScanTimestamps.put(packageName, elapsedRealtime);
        }
        synchronized (this) {
            if (mWifiScanner == null) {
                mWifiScanner = mWifiInjector.getWifiScanner();
            }
            if (mInIdleMode) {
                // Need to send an immediate scan result broadcast in case the
                // caller is waiting for a result ..

                // TODO: investigate if the logic to cancel scans when idle can move to
                // WifiScanningServiceImpl.  This will 1 - clean up WifiServiceImpl and 2 -
                // avoid plumbing an awkward path to report a cancelled/failed scan.  This will
                // be sent directly until b/31398592 is fixed.
                sendFailedScanBroadcast();
                mScanPending = true;
                return;
            }
        }
        if (settings != null) {
            settings = new ScanSettings(settings);
            if (!settings.isValid()) {
                Slog.e(TAG, "invalid scan setting");
                return;
            }
        }
        if (workSource != null) {
            enforceWorkSourcePermission();
            // WifiManager currently doesn't use names, so need to clear names out of the
            // supplied WorkSource to allow future WorkSource combining.
            workSource.clearNames();
        }
        if (workSource == null && Binder.getCallingUid() >= 0) {
            workSource = new WorkSource(Binder.getCallingUid());
        }
        mWifiStateMachine.startScan(Binder.getCallingUid(), scanRequestCounter++,
                settings, workSource);
    }

    // Send a failed scan broadcast to indicate the current scan request failed.
    private void sendFailedScanBroadcast() {
        // clear calling identity to send broadcast
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
        } finally {
            // restore calling identity
            Binder.restoreCallingIdentity(callingIdentity);
        }

    }

    // Check if the request comes from background.
    private boolean isRequestFromBackground(String packageName) {
        // Requests from system or wifi are not background.
        if (Binder.getCallingUid() == Process.SYSTEM_UID
                || Binder.getCallingUid() == Process.WIFI_UID) {
            return false;
        }
        mAppOps.checkPackage(Binder.getCallingUid(), packageName);
        if (mBackgroundThrottlePackageWhitelist.contains(packageName)) {
            return false;
        }

        // getPackageImportance requires PACKAGE_USAGE_STATS permission, so clearing the incoming
        // identify so the permission check can be done on system process where wifi runs in.
        long callingIdentity = Binder.clearCallingIdentity();
        try {
            return mActivityManager.getPackageImportance(packageName)
                    > BACKGROUND_IMPORTANCE_CUTOFF;
        } finally {
            Binder.restoreCallingIdentity(callingIdentity);
        }
    }

    @Override
    public String getCurrentNetworkWpsNfcConfigurationToken() {
        enforceConnectivityInternalPermission();
        mLog.info("getCurrentNetworkWpsNfcConfigurationToken uid=%")
                .c(Binder.getCallingUid()).flush();
        // TODO Add private logging for netId b/33807876
        return mWifiStateMachine.syncGetCurrentNetworkWpsNfcConfigurationToken();
    }

    boolean mInIdleMode;
    boolean mScanPending;

    void handleIdleModeChanged() {
        boolean doScan = false;
        synchronized (this) {
            boolean idle = mPowerManager.isDeviceIdleMode();
            if (mInIdleMode != idle) {
                mInIdleMode = idle;
                if (!idle) {
                    if (mScanPending) {
                        mScanPending = false;
                        doScan = true;
                    }
                }
            }
        }
        if (doScan) {
            // Someone requested a scan while we were idle; do a full scan now.
            // The package name doesn't matter as the request comes from System UID.
            startScan(null, null, "");
        }
    }

    private boolean checkNetworkSettingsPermission(int pid, int uid) {
        return mContext.checkPermission(android.Manifest.permission.NETWORK_SETTINGS, pid, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void enforceNetworkSettingsPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.NETWORK_SETTINGS,
                "WifiService");
    }

    private void enforceNetworkStackPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.NETWORK_STACK,
                "WifiService");
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE,
                "WifiService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE,
                "WifiService");
    }

    private void enforceLocationHardwarePermission() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.LOCATION_HARDWARE,
                "LocationHardware");
    }

    private void enforceReadCredentialPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.READ_WIFI_CREDENTIAL,
                                                "WifiService");
    }

    private void enforceWorkSourcePermission() {
        mContext.enforceCallingPermission(android.Manifest.permission.UPDATE_DEVICE_STATS,
                "WifiService");

    }

    private void enforceMulticastChangePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                "WifiService");
    }

    private void enforceConnectivityInternalPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL,
                "ConnectivityService");
    }

    private void enforceLocationPermission(String pkgName, int uid) {
        mWifiPermissionsUtil.enforceLocationPermission(pkgName, uid);
    }

    /**
     * see {@link android.net.wifi.WifiManager#setWifiEnabled(boolean)}
     * @param enable {@code true} to enable, {@code false} to disable.
     * @return {@code true} if the enable/disable operation was
     *         started or is already in the queue.
     */
    @Override
    public synchronized boolean setWifiEnabled(String packageName, boolean enable)
            throws RemoteException {
        enforceChangePermission();
        Slog.d(TAG, "setWifiEnabled: " + enable + " pid=" + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid() + ", package=" + packageName);
        mLog.info("setWifiEnabled package=% uid=% enable=%").c(packageName)
                .c(Binder.getCallingUid()).c(enable).flush();

        boolean isFromSettings = checkNetworkSettingsPermission(
                Binder.getCallingPid(), Binder.getCallingUid());

        // If Airplane mode is enabled, only Settings is allowed to toggle Wifi
        if (mSettingsStore.isAirplaneModeOn() && !isFromSettings) {
            mLog.info("setWifiEnabled in Airplane mode: only Settings can enable wifi").flush();
            return false;
        }

        // If SoftAp is enabled, only Settings is allowed to toggle wifi
        boolean apEnabled =
                mWifiStateMachine.syncGetWifiApState() != WifiManager.WIFI_AP_STATE_DISABLED;

        if (apEnabled && !isFromSettings) {
            mLog.info("setWifiEnabled SoftAp not disabled: only Settings can enable wifi").flush();
            return false;
        }

        /*
        * Caller might not have WRITE_SECURE_SETTINGS,
        * only CHANGE_WIFI_STATE is enforced
        */
        long ident = Binder.clearCallingIdentity();
        try {
            if (! mSettingsStore.handleWifiToggled(enable)) {
                // Nothing to do if wifi cannot be toggled
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }


        if (mPermissionReviewRequired) {
            final int wiFiEnabledState = getWifiEnabledState();
            if (enable) {
                if (wiFiEnabledState == WifiManager.WIFI_STATE_DISABLING
                        || wiFiEnabledState == WifiManager.WIFI_STATE_DISABLED) {
                    if (startConsentUi(packageName, Binder.getCallingUid(),
                            WifiManager.ACTION_REQUEST_ENABLE)) {
                        return true;
                    }
                }
            } else if (wiFiEnabledState == WifiManager.WIFI_STATE_ENABLING
                    || wiFiEnabledState == WifiManager.WIFI_STATE_ENABLED) {
                if (startConsentUi(packageName, Binder.getCallingUid(),
                        WifiManager.ACTION_REQUEST_DISABLE)) {
                    return true;
                }
            }
        }

        mWifiController.sendMessage(CMD_WIFI_TOGGLED);
        return true;
    }

    /**
     * see {@link WifiManager#getWifiState()}
     * @return One of {@link WifiManager#WIFI_STATE_DISABLED},
     *         {@link WifiManager#WIFI_STATE_DISABLING},
     *         {@link WifiManager#WIFI_STATE_ENABLED},
     *         {@link WifiManager#WIFI_STATE_ENABLING},
     *         {@link WifiManager#WIFI_STATE_UNKNOWN}
     */
    @Override
    public int getWifiEnabledState() {
        enforceAccessPermission();
        mLog.info("getWifiEnabledState uid=%").c(Binder.getCallingUid()).flush();
        return mWifiStateMachine.syncGetWifiState();
    }

    /**
     * see {@link android.net.wifi.WifiManager#setWifiApEnabled(WifiConfiguration, boolean)}
     * @param wifiConfig SSID, security and channel details as
     *        part of WifiConfiguration
     * @param enabled true to enable and false to disable
     */
    @Override
    public void setWifiApEnabled(WifiConfiguration wifiConfig, boolean enabled) {
        enforceChangePermission();
        mWifiPermissionsUtil.enforceTetherChangePermission(mContext);

        mLog.info("setWifiApEnabled uid=% enable=%").c(Binder.getCallingUid()).c(enabled).flush();

        if (mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING)) {
            throw new SecurityException("DISALLOW_CONFIG_TETHERING is enabled for this user.");
        }
        // null wifiConfig is a meaningful input for CMD_SET_AP
        if (wifiConfig == null || isValid(wifiConfig)) {
            int mode = WifiManager.IFACE_IP_MODE_UNSPECIFIED;
            SoftApModeConfiguration softApConfig = new SoftApModeConfiguration(mode, wifiConfig);
            mWifiController.sendMessage(CMD_SET_AP, enabled ? 1 : 0, 0, softApConfig);
        } else {
            Slog.e(TAG, "Invalid WifiConfiguration");
        }
    }

    /**
     * see {@link WifiManager#getWifiApState()}
     * @return One of {@link WifiManager#WIFI_AP_STATE_DISABLED},
     *         {@link WifiManager#WIFI_AP_STATE_DISABLING},
     *         {@link WifiManager#WIFI_AP_STATE_ENABLED},
     *         {@link WifiManager#WIFI_AP_STATE_ENABLING},
     *         {@link WifiManager#WIFI_AP_STATE_FAILED}
     */
    @Override
    public int getWifiApEnabledState() {
        enforceAccessPermission();
        mLog.info("getWifiApEnabledState uid=%").c(Binder.getCallingUid()).flush();
        return mWifiStateMachine.syncGetWifiApState();
    }

    /**
     * see {@link android.net.wifi.WifiManager#updateInterfaceIpState(String, int)}
     *
     * The possible modes include: {@link WifiManager#IFACE_IP_MODE_TETHERED},
     *                             {@link WifiManager#IFACE_IP_MODE_LOCAL_ONLY},
     *                             {@link WifiManager#IFACE_IP_MODE_CONFIGURATION_ERROR}
     *
     * @param ifaceName String name of the updated interface
     * @param mode new operating mode of the interface
     *
     * @throws SecurityException if the caller does not have permission to call update
     */
    @Override
    public void updateInterfaceIpState(String ifaceName, int mode) {
        // NETWORK_STACK is a signature only permission.
        enforceNetworkStackPermission();

        // hand off the work to our handler thread
        mClientHandler.post(() -> {
            updateInterfaceIpStateInternal(ifaceName, mode);
        });
    }

    private void updateInterfaceIpStateInternal(String ifaceName, int mode) {
        // update interface IP state related to tethering and hotspot
        synchronized (mLocalOnlyHotspotRequests) {
            // update the mode tracker here - we clear out state below
            Integer previousMode = WifiManager.IFACE_IP_MODE_UNSPECIFIED;
            if (ifaceName != null) {
                previousMode = mIfaceIpModes.put(ifaceName, mode);
            }
            Slog.d(TAG, "updateInterfaceIpState: ifaceName=" + ifaceName + " mode=" + mode
                    + " previous mode= " + previousMode);

            switch (mode) {
                case WifiManager.IFACE_IP_MODE_LOCAL_ONLY:
                    // first make sure we have registered requests..  otherwise clean up
                    if (mLocalOnlyHotspotRequests.isEmpty()) {
                        // we don't have requests...  stop the hotspot
                        stopSoftAp();
                        updateInterfaceIpStateInternal(null, WifiManager.IFACE_IP_MODE_UNSPECIFIED);
                        return;
                    }
                    // LOHS is ready to go!  Call our registered requestors!
                    sendHotspotStartedMessageToAllLOHSRequestInfoEntriesLocked();
                    break;
                case WifiManager.IFACE_IP_MODE_TETHERED:
                    // we have tethered an interface. we don't really act on this now other than if
                    // we have LOHS requests, and this is an issue.  return incompatible mode for
                    // onFailed for the registered requestors since this can result from a race
                    // between a tether request and a hotspot request (tethering wins).
                    sendHotspotFailedMessageToAllLOHSRequestInfoEntriesLocked(
                            LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE);
                    break;
                case WifiManager.IFACE_IP_MODE_CONFIGURATION_ERROR:
                    // there was an error setting up the hotspot...  trigger onFailed for the
                    // registered LOHS requestors
                    sendHotspotFailedMessageToAllLOHSRequestInfoEntriesLocked(
                            LocalOnlyHotspotCallback.ERROR_GENERIC);
                    updateInterfaceIpStateInternal(null, WifiManager.IFACE_IP_MODE_UNSPECIFIED);
                    break;
                case WifiManager.IFACE_IP_MODE_UNSPECIFIED:
                    if (ifaceName == null) {
                        // interface name is null, this is due to softap teardown.  clear all
                        // entries for now.
                        // TODO: Deal with individual interfaces when we receive updates for them
                        mIfaceIpModes.clear();
                        return;
                    }
                    break;
                default:
                    mLog.warn("updateInterfaceIpStateInternal: unknown mode %").c(mode).flush();
            }
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#startSoftAp(WifiConfiguration)}
     * @param wifiConfig SSID, security and channel details as part of WifiConfiguration
     * @return {@code true} if softap start was triggered
     * @throws SecurityException if the caller does not have permission to start softap
     */
    @Override
    public boolean startSoftAp(WifiConfiguration wifiConfig) {
        // NETWORK_STACK is a signature only permission.
        enforceNetworkStackPermission();

        mLog.info("startSoftAp uid=%").c(Binder.getCallingUid()).flush();

        synchronized (mLocalOnlyHotspotRequests) {
            // If a tethering request comes in while we have LOHS running (or requested), call stop
            // for softap mode and restart softap with the tethering config.
            if (!mLocalOnlyHotspotRequests.isEmpty()) {
                stopSoftApInternal();
            }

            return startSoftApInternal(wifiConfig, WifiManager.IFACE_IP_MODE_TETHERED);
        }
    }

    /**
     * Internal method to start softap mode. Callers of this method should have already checked
     * proper permissions beyond the NetworkStack permission.
     */
    private boolean startSoftApInternal(WifiConfiguration wifiConfig, int mode) {
        mLog.trace("startSoftApInternal uid=% mode=%")
                .c(Binder.getCallingUid()).c(mode).flush();

        // null wifiConfig is a meaningful input for CMD_SET_AP
        if (wifiConfig == null || isValid(wifiConfig)) {
            SoftApModeConfiguration softApConfig = new SoftApModeConfiguration(mode, wifiConfig);
            mWifiController.sendMessage(CMD_SET_AP, 1, 0, softApConfig);
            return true;
        }
        Slog.e(TAG, "Invalid WifiConfiguration");
        return false;
    }

    /**
     * see {@link android.net.wifi.WifiManager#stopSoftAp()}
     * @return {@code true} if softap stop was triggered
     * @throws SecurityException if the caller does not have permission to stop softap
     */
    @Override
    public boolean stopSoftAp() {
        // NETWORK_STACK is a signature only permission.
        enforceNetworkStackPermission();

        // only permitted callers are allowed to this point - they must have gone through
        // connectivity service since this method is protected with the NETWORK_STACK PERMISSION

        mLog.info("stopSoftAp uid=%").c(Binder.getCallingUid()).flush();

        synchronized (mLocalOnlyHotspotRequests) {
            // If a tethering request comes in while we have LOHS running (or requested), call stop
            // for softap mode and restart softap with the tethering config.
            if (!mLocalOnlyHotspotRequests.isEmpty()) {
                mLog.trace("Call to stop Tethering while LOHS is active,"
                        + " Registered LOHS callers will be updated when softap stopped.");
            }

            return stopSoftApInternal();
        }
    }

    /**
     * Internal method to stop softap mode.  Callers of this method should have already checked
     * proper permissions beyond the NetworkStack permission.
     */
    private boolean stopSoftApInternal() {
        mLog.trace("stopSoftApInternal uid=%").c(Binder.getCallingUid()).flush();

        mWifiController.sendMessage(CMD_SET_AP, 0, 0);
        return true;
    }

    /**
     * Private method to handle SoftAp state changes
     */
    private void handleWifiApStateChange(
            int currentState, int previousState, int errorCode, String ifaceName, int mode) {
        // The AP state update from WifiStateMachine for softap
        Slog.d(TAG, "handleWifiApStateChange: currentState=" + currentState
                + " previousState=" + previousState + " errorCode= " + errorCode
                + " ifaceName=" + ifaceName + " mode=" + mode);

        // check if we have a failure - since it is possible (worst case scenario where
        // WifiController and WifiStateMachine are out of sync wrt modes) to get two FAILED
        // notifications in a row, we need to handle this first.
        if (currentState == WIFI_AP_STATE_FAILED) {
            // update registered LOHS callbacks if we see a failure
            synchronized (mLocalOnlyHotspotRequests) {
                int errorToReport = ERROR_GENERIC;
                if (errorCode == SAP_START_FAILURE_NO_CHANNEL) {
                    errorToReport = ERROR_NO_CHANNEL;
                }
                // holding the required lock: send message to requestors and clear the list
                sendHotspotFailedMessageToAllLOHSRequestInfoEntriesLocked(
                        errorToReport);
                // also need to clear interface ip state - send null for now since we don't know
                // what interface (and we have one anyway)
                updateInterfaceIpStateInternal(null, WifiManager.IFACE_IP_MODE_UNSPECIFIED);
            }
            return;
        }

        if (currentState == WIFI_AP_STATE_DISABLING || currentState == WIFI_AP_STATE_DISABLED) {
            // softap is shutting down or is down...  let requestors know via the onStopped call
            synchronized (mLocalOnlyHotspotRequests) {
                // if we are currently in hotspot mode, then trigger onStopped for registered
                // requestors, otherwise something odd happened and we should clear state
                if (mIfaceIpModes.contains(WifiManager.IFACE_IP_MODE_LOCAL_ONLY)) {
                    // holding the required lock: send message to requestors and clear the list
                    sendHotspotStoppedMessageToAllLOHSRequestInfoEntriesLocked();
                } else {
                    // LOHS not active: report an error (still holding the required lock)
                    sendHotspotFailedMessageToAllLOHSRequestInfoEntriesLocked(ERROR_GENERIC);
                }
                // also clear interface ip state - send null for now since we don't know what
                // interface (and we only have one anyway)
                updateInterfaceIpState(null, WifiManager.IFACE_IP_MODE_UNSPECIFIED);
            }
            return;
        }

        // remaining states are enabling or enabled...  those are not used for the callbacks
    }

    /**
     * Helper method to send a HOTSPOT_FAILED message to all registered LocalOnlyHotspotRequest
     * callers and clear the registrations.
     *
     * Callers should already hold the mLocalOnlyHotspotRequests lock.
     */
    private void sendHotspotFailedMessageToAllLOHSRequestInfoEntriesLocked(int arg1) {
        for (LocalOnlyHotspotRequestInfo requestor : mLocalOnlyHotspotRequests.values()) {
            try {
                requestor.sendHotspotFailedMessage(arg1);
                requestor.unlinkDeathRecipient();
            } catch (RemoteException e) {
                // This will be cleaned up by binder death handling
            }
        }

        // Since all callers were notified, now clear the registrations.
        mLocalOnlyHotspotRequests.clear();
    }

    /**
     * Helper method to send a HOTSPOT_STOPPED message to all registered LocalOnlyHotspotRequest
     * callers and clear the registrations.
     *
     * Callers should already hold the mLocalOnlyHotspotRequests lock.
     */
    private void sendHotspotStoppedMessageToAllLOHSRequestInfoEntriesLocked() {
        for (LocalOnlyHotspotRequestInfo requestor : mLocalOnlyHotspotRequests.values()) {
            try {
                requestor.sendHotspotStoppedMessage();
                requestor.unlinkDeathRecipient();
            } catch (RemoteException e) {
                // This will be cleaned up by binder death handling
            }
        }

        // Since all callers were notified, now clear the registrations.
        mLocalOnlyHotspotRequests.clear();
    }

    /**
     * Helper method to send a HOTSPOT_STARTED message to all registered LocalOnlyHotspotRequest
     * callers.
     *
     * Callers should already hold the mLocalOnlyHotspotRequests lock.
     */
    private void sendHotspotStartedMessageToAllLOHSRequestInfoEntriesLocked() {
        for (LocalOnlyHotspotRequestInfo requestor : mLocalOnlyHotspotRequests.values()) {
            try {
                requestor.sendHotspotStartedMessage(mLocalOnlyHotspotConfig);
            } catch (RemoteException e) {
                // This will be cleaned up by binder death handling
            }
        }
    }

    /**
     * Temporary method used for testing while startLocalOnlyHotspot is not fully implemented.  This
     * method allows unit tests to register callbacks directly for testing mechanisms triggered by
     * softap mode changes.
     */
    @VisibleForTesting
    void registerLOHSForTest(int pid, LocalOnlyHotspotRequestInfo request) {
        mLocalOnlyHotspotRequests.put(pid, request);
    }

    /**
     * Method to start LocalOnlyHotspot.  In this method, permissions, settings and modes are
     * checked to verify that we can enter softapmode.  This method returns
     * {@link LocalOnlyHotspotCallback#REQUEST_REGISTERED} if we will attempt to start, otherwise,
     * possible startup erros may include tethering being disallowed failure reason {@link
     * LocalOnlyHotspotCallback#ERROR_TETHERING_DISALLOWED} or an incompatible mode failure reason
     * {@link LocalOnlyHotspotCallback#ERROR_INCOMPATIBLE_MODE}.
     *
     * see {@link WifiManager#startLocalOnlyHotspot(LocalOnlyHotspotCallback)}
     *
     * @param messenger Messenger to send messages to the corresponding WifiManager.
     * @param binder IBinder instance to allow cleanup if the app dies
     * @param packageName String name of the calling package
     *
     * @return int return code for attempt to start LocalOnlyHotspot.
     *
     * @throws SecurityException if the caller does not have permission to start a Local Only
     * Hotspot.
     * @throws IllegalStateException if the caller attempts to start the LocalOnlyHotspot while they
     * have an outstanding request.
     */
    @Override
    public int startLocalOnlyHotspot(Messenger messenger, IBinder binder, String packageName) {
        // first check if the caller has permission to start a local only hotspot
        // need to check for WIFI_STATE_CHANGE and location permission
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();

        enforceChangePermission();
        enforceLocationPermission(packageName, uid);
        // also need to verify that Locations services are enabled.
        if (mSettingsStore.getLocationModeSetting(mContext) == Settings.Secure.LOCATION_MODE_OFF) {
            throw new SecurityException("Location mode is not enabled.");
        }

        // verify that tethering is not disabled
        if (mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING)) {
            return LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED;
        }

        // the app should be in the foreground
        try {
            if (!mFrameworkFacade.isAppForeground(uid)) {
                return LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE;
            }
        } catch (RemoteException e) {
            mLog.warn("RemoteException during isAppForeground when calling startLOHS");
            return LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE;
        }

        mLog.info("startLocalOnlyHotspot uid=% pid=%").c(uid).c(pid).flush();

        synchronized (mLocalOnlyHotspotRequests) {
            // check if we are currently tethering
            if (mIfaceIpModes.contains(WifiManager.IFACE_IP_MODE_TETHERED)) {
                // Tethering is enabled, cannot start LocalOnlyHotspot
                mLog.info("Cannot start localOnlyHotspot when WiFi Tethering is active.");
                return LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE;
            }

            // does this caller already have a request?
            LocalOnlyHotspotRequestInfo request = mLocalOnlyHotspotRequests.get(pid);
            if (request != null) {
                mLog.trace("caller already has an active request");
                throw new IllegalStateException(
                        "Caller already has an active LocalOnlyHotspot request");
            }

            // now create the new LOHS request info object
            request = new LocalOnlyHotspotRequestInfo(binder, messenger,
                    new LocalOnlyRequestorCallback());

            // check current operating state and take action if needed
            if (mIfaceIpModes.contains(WifiManager.IFACE_IP_MODE_LOCAL_ONLY)) {
                // LOHS is already active, send out what is running
                try {
                    mLog.trace("LOHS already up, trigger onStarted callback");
                    request.sendHotspotStartedMessage(mLocalOnlyHotspotConfig);
                } catch (RemoteException e) {
                    return LocalOnlyHotspotCallback.ERROR_GENERIC;
                }
            } else if (mLocalOnlyHotspotRequests.isEmpty()) {
                // this is the first request, then set up our config and start LOHS
                mLocalOnlyHotspotConfig =
                        WifiApConfigStore.generateLocalOnlyHotspotConfig(mContext);
                startSoftApInternal(mLocalOnlyHotspotConfig, WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
            }

            mLocalOnlyHotspotRequests.put(pid, request);
            return LocalOnlyHotspotCallback.REQUEST_REGISTERED;
        }
    }

    /**
     * see {@link WifiManager#stopLocalOnlyHotspot()}
     *
     * @throws SecurityException if the caller does not have permission to stop a Local Only
     * Hotspot.
     */
    @Override
    public void stopLocalOnlyHotspot() {
        // first check if the caller has permission to stop a local only hotspot
        enforceChangePermission();
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();

        mLog.info("stopLocalOnlyHotspot uid=% pid=%").c(uid).c(pid).flush();

        synchronized (mLocalOnlyHotspotRequests) {
            // was the caller already registered?  check request tracker - return false if not
            LocalOnlyHotspotRequestInfo requestInfo = mLocalOnlyHotspotRequests.get(pid);
            if (requestInfo == null) {
                return;
            }
            requestInfo.unlinkDeathRecipient();
            unregisterCallingAppAndStopLocalOnlyHotspot(requestInfo);
        } // end synchronized
    }

    /**
     * Helper method to unregister LocalOnlyHotspot requestors and stop the hotspot if needed.
     */
    private void unregisterCallingAppAndStopLocalOnlyHotspot(LocalOnlyHotspotRequestInfo request) {
        mLog.trace("unregisterCallingAppAndStopLocalOnlyHotspot pid=%").c(request.getPid()).flush();

        synchronized (mLocalOnlyHotspotRequests) {
            if (mLocalOnlyHotspotRequests.remove(request.getPid()) == null) {
                mLog.trace("LocalOnlyHotspotRequestInfo not found to remove");
                return;
            }

            if (mLocalOnlyHotspotRequests.isEmpty()) {
                mLocalOnlyHotspotConfig = null;
                updateInterfaceIpStateInternal(null, WifiManager.IFACE_IP_MODE_UNSPECIFIED);
                // if that was the last caller, then call stopSoftAp as WifiService
                long identity = Binder.clearCallingIdentity();
                try {
                    stopSoftApInternal();
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }
    }

    /**
     * see {@link WifiManager#watchLocalOnlyHotspot(LocalOnlyHotspotObserver)}
     *
     * This call requires the android.permission.NETWORK_SETTINGS permission.
     *
     * @param messenger Messenger to send messages to the corresponding WifiManager.
     * @param binder IBinder instance to allow cleanup if the app dies
     *
     * @throws SecurityException if the caller does not have permission to watch Local Only Hotspot
     * status updates.
     * @throws IllegalStateException if the caller attempts to watch LocalOnlyHotspot updates with
     * an existing subscription.
     */
    @Override
    public void startWatchLocalOnlyHotspot(Messenger messenger, IBinder binder) {
        final String packageName = mContext.getOpPackageName();

        // NETWORK_SETTINGS is a signature only permission.
        enforceNetworkSettingsPermission();

        throw new UnsupportedOperationException("LocalOnlyHotspot is still in development");
    }

    /**
     * see {@link WifiManager#unregisterLocalOnlyHotspotObserver()}
     */
    @Override
    public void stopWatchLocalOnlyHotspot() {
        // NETWORK_STACK is a signature only permission.
        enforceNetworkSettingsPermission();
        throw new UnsupportedOperationException("LocalOnlyHotspot is still in development");
    }

    /**
     * see {@link WifiManager#getWifiApConfiguration()}
     * @return soft access point configuration
     * @throws SecurityException if the caller does not have permission to retrieve the softap
     * config
     */
    @Override
    public WifiConfiguration getWifiApConfiguration() {
        enforceAccessPermission();
        int uid = Binder.getCallingUid();
        // only allow Settings UI to get the saved SoftApConfig
        if (!mWifiPermissionsUtil.checkConfigOverridePermission(uid)) {
            // random apps should not be allowed to read the user specified config
            throw new SecurityException("App not allowed to read or update stored WiFi Ap config "
                    + "(uid = " + uid + ")");
        }
        mLog.info("getWifiApConfiguration uid=%").c(uid).flush();
        return mWifiStateMachine.syncGetWifiApConfiguration();
    }

    /**
     * see {@link WifiManager#setWifiApConfiguration(WifiConfiguration)}
     * @param wifiConfig WifiConfiguration details for soft access point
     * @throws SecurityException if the caller does not have permission to write the sotap config
     */
    @Override
    public void setWifiApConfiguration(WifiConfiguration wifiConfig) {
        enforceChangePermission();
        int uid = Binder.getCallingUid();
        // only allow Settings UI to write the stored SoftApConfig
        if (!mWifiPermissionsUtil.checkConfigOverridePermission(uid)) {
            // random apps should not be allowed to read the user specified config
            throw new SecurityException("App not allowed to read or update stored WiFi AP config "
                    + "(uid = " + uid + ")");
        }
        mLog.info("setWifiApConfiguration uid=%").c(uid).flush();
        if (wifiConfig == null)
            return;
        if (isValid(wifiConfig)) {
            mWifiStateMachine.setWifiApConfiguration(wifiConfig);
        } else {
            Slog.e(TAG, "Invalid WifiConfiguration");
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#isScanAlwaysAvailable()}
     */
    @Override
    public boolean isScanAlwaysAvailable() {
        enforceAccessPermission();
        mLog.info("isScanAlwaysAvailable uid=%").c(Binder.getCallingUid()).flush();
        return mSettingsStore.isScanAlwaysAvailable();
    }

    /**
     * see {@link android.net.wifi.WifiManager#disconnect()}
     */
    @Override
    public void disconnect() {
        enforceChangePermission();
        mLog.info("disconnect uid=%").c(Binder.getCallingUid()).flush();
        mWifiStateMachine.disconnectCommand();
    }

    /**
     * see {@link android.net.wifi.WifiManager#reconnect()}
     */
    @Override
    public void reconnect() {
        enforceChangePermission();
        mLog.info("reconnect uid=%").c(Binder.getCallingUid()).flush();
        mWifiStateMachine.reconnectCommand(new WorkSource(Binder.getCallingUid()));
    }

    /**
     * see {@link android.net.wifi.WifiManager#reassociate()}
     */
    @Override
    public void reassociate() {
        enforceChangePermission();
        mLog.info("reassociate uid=%").c(Binder.getCallingUid()).flush();
        mWifiStateMachine.reassociateCommand();
    }

    /**
     * see {@link android.net.wifi.WifiManager#getSupportedFeatures}
     */
    @Override
    public int getSupportedFeatures() {
        enforceAccessPermission();
        mLog.info("getSupportedFeatures uid=%").c(Binder.getCallingUid()).flush();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncGetSupportedFeatures(mWifiStateMachineChannel);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return 0;
        }
    }

    @Override
    public void requestActivityInfo(ResultReceiver result) {
        Bundle bundle = new Bundle();
        mLog.info("requestActivityInfo uid=%").c(Binder.getCallingUid()).flush();
        bundle.putParcelable(BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY, reportActivityInfo());
        result.send(0, bundle);
    }

    /**
     * see {@link android.net.wifi.WifiManager#getControllerActivityEnergyInfo(int)}
     */
    @Override
    public WifiActivityEnergyInfo reportActivityInfo() {
        enforceAccessPermission();
        mLog.info("reportActivityInfo uid=%").c(Binder.getCallingUid()).flush();
        if ((getSupportedFeatures() & WifiManager.WIFI_FEATURE_LINK_LAYER_STATS) == 0) {
            return null;
        }
        WifiLinkLayerStats stats;
        WifiActivityEnergyInfo energyInfo = null;
        if (mWifiStateMachineChannel != null) {
            stats = mWifiStateMachine.syncGetLinkLayerStats(mWifiStateMachineChannel);
            if (stats != null) {
                final long rxIdleCurrent = mContext.getResources().getInteger(
                        com.android.internal.R.integer.config_wifi_idle_receive_cur_ma);
                final long rxCurrent = mContext.getResources().getInteger(
                        com.android.internal.R.integer.config_wifi_active_rx_cur_ma);
                final long txCurrent = mContext.getResources().getInteger(
                        com.android.internal.R.integer.config_wifi_tx_cur_ma);
                final double voltage = mContext.getResources().getInteger(
                        com.android.internal.R.integer.config_wifi_operating_voltage_mv)
                        / 1000.0;

                final long rxIdleTime = stats.on_time - stats.tx_time - stats.rx_time;
                final long[] txTimePerLevel;
                if (stats.tx_time_per_level != null) {
                    txTimePerLevel = new long[stats.tx_time_per_level.length];
                    for (int i = 0; i < txTimePerLevel.length; i++) {
                        txTimePerLevel[i] = stats.tx_time_per_level[i];
                        // TODO(b/27227497): Need to read the power consumed per level from config
                    }
                } else {
                    // This will happen if the HAL get link layer API returned null.
                    txTimePerLevel = new long[0];
                }
                final long energyUsed = (long)((stats.tx_time * txCurrent +
                        stats.rx_time * rxCurrent +
                        rxIdleTime * rxIdleCurrent) * voltage);
                if (VDBG || rxIdleTime < 0 || stats.on_time < 0 || stats.tx_time < 0 ||
                        stats.rx_time < 0 || energyUsed < 0) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(" rxIdleCur=" + rxIdleCurrent);
                    sb.append(" rxCur=" + rxCurrent);
                    sb.append(" txCur=" + txCurrent);
                    sb.append(" voltage=" + voltage);
                    sb.append(" on_time=" + stats.on_time);
                    sb.append(" tx_time=" + stats.tx_time);
                    sb.append(" tx_time_per_level=" + Arrays.toString(txTimePerLevel));
                    sb.append(" rx_time=" + stats.rx_time);
                    sb.append(" rxIdleTime=" + rxIdleTime);
                    sb.append(" energy=" + energyUsed);
                    Log.d(TAG, " reportActivityInfo: " + sb.toString());
                }

                // Convert the LinkLayerStats into EnergyActivity
                energyInfo = new WifiActivityEnergyInfo(mClock.getElapsedSinceBootMillis(),
                        WifiActivityEnergyInfo.STACK_STATE_STATE_IDLE, stats.tx_time,
                        txTimePerLevel, stats.rx_time, rxIdleTime, energyUsed);
            }
            if (energyInfo != null && energyInfo.isValid()) {
                return energyInfo;
            } else {
                return null;
            }
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return null;
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#getConfiguredNetworks()}
     * @return the list of configured networks
     */
    @Override
    public ParceledListSlice<WifiConfiguration> getConfiguredNetworks() {
        enforceAccessPermission();
        mLog.info("getConfiguredNetworks uid=%").c(Binder.getCallingUid()).flush();
        if (mWifiStateMachineChannel != null) {
            List<WifiConfiguration> configs = mWifiStateMachine.syncGetConfiguredNetworks(
                    Binder.getCallingUid(), mWifiStateMachineChannel);
            if (configs != null) {
                return new ParceledListSlice<WifiConfiguration>(configs);
            }
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
        }
        return null;
    }

    /**
     * see {@link android.net.wifi.WifiManager#getPrivilegedConfiguredNetworks()}
     * @return the list of configured networks with real preSharedKey
     */
    @Override
    public ParceledListSlice<WifiConfiguration> getPrivilegedConfiguredNetworks() {
        enforceReadCredentialPermission();
        enforceAccessPermission();
        mLog.info("getPrivilegedConfiguredNetworks uid=%").c(Binder.getCallingUid()).flush();
        if (mWifiStateMachineChannel != null) {
            List<WifiConfiguration> configs =
                    mWifiStateMachine.syncGetPrivilegedConfiguredNetwork(mWifiStateMachineChannel);
            if (configs != null) {
                return new ParceledListSlice<WifiConfiguration>(configs);
            }
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
        }
        return null;
    }

    /**
     * Returns a WifiConfiguration for a Passpoint network matching this ScanResult.
     *
     * @param scanResult scanResult that represents the BSSID
     * @return {@link WifiConfiguration} that matches this BSSID or null
     */
    @Override
    public WifiConfiguration getMatchingWifiConfig(ScanResult scanResult) {
        enforceAccessPermission();
        mLog.info("getMatchingWifiConfig uid=%").c(Binder.getCallingUid()).flush();
        if (!mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_PASSPOINT)) {
            throw new UnsupportedOperationException("Passpoint not enabled");
        }
        return mWifiStateMachine.syncGetMatchingWifiConfig(scanResult, mWifiStateMachineChannel);
    }

    /**
     * Returns list of OSU (Online Sign-Up) providers associated with the given Passpoint network.
     *
     * @param scanResult scanResult of the Passpoint AP
     * @return List of {@link OsuProvider}
     */
    @Override
    public List<OsuProvider> getMatchingOsuProviders(ScanResult scanResult) {
        enforceAccessPermission();
        mLog.info("getMatchingOsuProviders uid=%").c(Binder.getCallingUid()).flush();
        if (!mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_PASSPOINT)) {
            throw new UnsupportedOperationException("Passpoint not enabled");
        }
        return mWifiStateMachine.syncGetMatchingOsuProviders(scanResult, mWifiStateMachineChannel);
    }

    /**
     * see {@link android.net.wifi.WifiManager#addOrUpdateNetwork(WifiConfiguration)}
     * @return the supplicant-assigned identifier for the new or updated
     * network if the operation succeeds, or {@code -1} if it fails
     */
    @Override
    public int addOrUpdateNetwork(WifiConfiguration config) {
        enforceChangePermission();
        mLog.info("addOrUpdateNetwork uid=%").c(Binder.getCallingUid()).flush();

        // Previously, this API is overloaded for installing Passpoint profiles.  Now
        // that we have a dedicated API for doing it, redirect the call to the dedicated API.
        if (config.isPasspoint()) {
            PasspointConfiguration passpointConfig =
                    PasspointProvider.convertFromWifiConfig(config);
            if (passpointConfig.getCredential() == null) {
                Slog.e(TAG, "Missing credential for Passpoint profile");
                return -1;
            }
            // Copy over certificates and keys.
            passpointConfig.getCredential().setCaCertificate(
                    config.enterpriseConfig.getCaCertificate());
            passpointConfig.getCredential().setClientCertificateChain(
                    config.enterpriseConfig.getClientCertificateChain());
            passpointConfig.getCredential().setClientPrivateKey(
                    config.enterpriseConfig.getClientPrivateKey());
            if (!addOrUpdatePasspointConfiguration(passpointConfig)) {
                Slog.e(TAG, "Failed to add Passpoint profile");
                return -1;
            }
            // There is no network ID associated with a Passpoint profile.
            return 0;
        }

        if (config != null) {
            //TODO: pass the Uid the WifiStateMachine as a message parameter
            Slog.i("addOrUpdateNetwork", " uid = " + Integer.toString(Binder.getCallingUid())
                    + " SSID " + config.SSID
                    + " nid=" + Integer.toString(config.networkId));
            if (config.networkId == WifiConfiguration.INVALID_NETWORK_ID) {
                config.creatorUid = Binder.getCallingUid();
            } else {
                config.lastUpdateUid = Binder.getCallingUid();
            }
            if (mWifiStateMachineChannel != null) {
                return mWifiStateMachine.syncAddOrUpdateNetwork(mWifiStateMachineChannel, config);
            } else {
                Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
                return -1;
            }
        } else {
            Slog.e(TAG, "bad network configuration");
            return -1;
        }
    }

    public static void verifyCert(X509Certificate caCert)
            throws GeneralSecurityException, IOException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        CertPathValidator validator =
                CertPathValidator.getInstance(CertPathValidator.getDefaultType());
        CertPath path = factory.generateCertPath(
                Arrays.asList(caCert));
        KeyStore ks = KeyStore.getInstance("AndroidCAStore");
        ks.load(null, null);
        PKIXParameters params = new PKIXParameters(ks);
        params.setRevocationEnabled(false);
        validator.validate(path, params);
    }

    /**
     * See {@link android.net.wifi.WifiManager#removeNetwork(int)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @return {@code true} if the operation succeeded
     */
    @Override
    public boolean removeNetwork(int netId) {
        enforceChangePermission();
        mLog.info("removeNetwork uid=%").c(Binder.getCallingUid()).flush();
        // TODO Add private logging for netId b/33807876
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncRemoveNetwork(mWifiStateMachineChannel, netId);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return false;
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#enableNetwork(int, boolean)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @param disableOthers if true, disable all other networks.
     * @return {@code true} if the operation succeeded
     */
    @Override
    public boolean enableNetwork(int netId, boolean disableOthers) {
        enforceChangePermission();
        // TODO b/33807876 Log netId
        mLog.info("enableNetwork uid=% disableOthers=%")
                .c(Binder.getCallingUid())
                .c(disableOthers).flush();

        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncEnableNetwork(mWifiStateMachineChannel, netId,
                    disableOthers);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return false;
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#disableNetwork(int)}
     * @param netId the integer that identifies the network configuration
     * to the supplicant
     * @return {@code true} if the operation succeeded
     */
    @Override
    public boolean disableNetwork(int netId) {
        enforceChangePermission();
        // TODO b/33807876 Log netId
        mLog.info("disableNetwork uid=%").c(Binder.getCallingUid()).flush();

        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncDisableNetwork(mWifiStateMachineChannel, netId);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return false;
        }
    }

    /**
     * See {@link android.net.wifi.WifiManager#getConnectionInfo()}
     * @return the Wi-Fi information, contained in {@link WifiInfo}.
     */
    @Override
    public WifiInfo getConnectionInfo(String callingPackage) {
        enforceAccessPermission();
        mLog.info("getConnectionInfo uid=%").c(Binder.getCallingUid()).flush();
        /*
         * Make sure we have the latest information, by sending
         * a status request to the supplicant.
         */
        return mWifiStateMachine.syncRequestConnectionInfo(callingPackage);
    }

    /**
     * Return the results of the most recent access point scan, in the form of
     * a list of {@link ScanResult} objects.
     * @return the list of results
     */
    @Override
    public List<ScanResult> getScanResults(String callingPackage) {
        enforceAccessPermission();
        int uid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        try {
            if (!mWifiPermissionsUtil.canAccessScanResults(callingPackage,
                      uid, Build.VERSION_CODES.M)) {
                return new ArrayList<ScanResult>();
            }
            if (mWifiScanner == null) {
                mWifiScanner = mWifiInjector.getWifiScanner();
            }
            return mWifiScanner.getSingleScanResults();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Add or update a Passpoint configuration.
     *
     * @param config The Passpoint configuration to be added
     * @return true on success or false on failure
     */
    @Override
    public boolean addOrUpdatePasspointConfiguration(PasspointConfiguration config) {
        enforceChangePermission();
        mLog.info("addorUpdatePasspointConfiguration uid=%").c(Binder.getCallingUid()).flush();
        if (!mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_PASSPOINT)) {
            throw new UnsupportedOperationException("Passpoint not enabled");
        }
        return mWifiStateMachine.syncAddOrUpdatePasspointConfig(mWifiStateMachineChannel, config,
                Binder.getCallingUid());
    }

    /**
     * Remove the Passpoint configuration identified by its FQDN (Fully Qualified Domain Name).
     *
     * @param fqdn The FQDN of the Passpoint configuration to be removed
     * @return true on success or false on failure
     */
    @Override
    public boolean removePasspointConfiguration(String fqdn) {
        enforceChangePermission();
        mLog.info("removePasspointConfiguration uid=%").c(Binder.getCallingUid()).flush();
        if (!mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_PASSPOINT)) {
            throw new UnsupportedOperationException("Passpoint not enabled");
        }
        return mWifiStateMachine.syncRemovePasspointConfig(mWifiStateMachineChannel, fqdn);
    }

    /**
     * Return the list of the installed Passpoint configurations.
     *
     * An empty list will be returned when no configuration is installed.
     *
     * @return A list of {@link PasspointConfiguration}
     */
    @Override
    public List<PasspointConfiguration> getPasspointConfigurations() {
        enforceAccessPermission();
        mLog.info("getPasspointConfigurations uid=%").c(Binder.getCallingUid()).flush();
        if (!mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_PASSPOINT)) {
            throw new UnsupportedOperationException("Passpoint not enabled");
        }
        return mWifiStateMachine.syncGetPasspointConfigs(mWifiStateMachineChannel);
    }

    /**
     * Query for a Hotspot 2.0 release 2 OSU icon
     * @param bssid The BSSID of the AP
     * @param fileName Icon file name
     */
    @Override
    public void queryPasspointIcon(long bssid, String fileName) {
        enforceAccessPermission();
        mLog.info("queryPasspointIcon uid=%").c(Binder.getCallingUid()).flush();
        if (!mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_PASSPOINT)) {
            throw new UnsupportedOperationException("Passpoint not enabled");
        }
        mWifiStateMachine.syncQueryPasspointIcon(mWifiStateMachineChannel, bssid, fileName);
    }

    /**
     * Match the currently associated network against the SP matching the given FQDN
     * @param fqdn FQDN of the SP
     * @return ordinal [HomeProvider, RoamingProvider, Incomplete, None, Declined]
     */
    @Override
    public int matchProviderWithCurrentNetwork(String fqdn) {
        mLog.info("matchProviderWithCurrentNetwork uid=%").c(Binder.getCallingUid()).flush();
        return mWifiStateMachine.matchProviderWithCurrentNetwork(mWifiStateMachineChannel, fqdn);
    }

    /**
     * Deauthenticate and set the re-authentication hold off time for the current network
     * @param holdoff hold off time in milliseconds
     * @param ess set if the hold off pertains to an ESS rather than a BSS
     */
    @Override
    public void deauthenticateNetwork(long holdoff, boolean ess) {
        mLog.info("deauthenticateNetwork uid=%").c(Binder.getCallingUid()).flush();
        mWifiStateMachine.deauthenticateNetwork(mWifiStateMachineChannel, holdoff, ess);
    }

    /**
     * Tell the supplicant to persist the current list of configured networks.
     * @return {@code true} if the operation succeeded
     *
     * TODO: deprecate this
     */
    @Override
    public boolean saveConfiguration() {
        enforceChangePermission();
        mLog.info("saveConfiguration uid=%").c(Binder.getCallingUid()).flush();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncSaveConfig(mWifiStateMachineChannel);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return false;
        }
    }

    /**
     * Set the country code
     * @param countryCode ISO 3166 country code.
     * @param persist {@code true} if the setting should be remembered.
     *
     * The persist behavior exists so that wifi can fall back to the last
     * persisted country code on a restart, when the locale information is
     * not available from telephony.
     */
    @Override
    public void setCountryCode(String countryCode, boolean persist) {
        Slog.i(TAG, "WifiService trying to set country code to " + countryCode +
                " with persist set to " + persist);
        enforceConnectivityInternalPermission();
        mLog.info("setCountryCode uid=%").c(Binder.getCallingUid()).flush();
        final long token = Binder.clearCallingIdentity();
        mCountryCode.setCountryCode(countryCode);
        Binder.restoreCallingIdentity(token);
    }

     /**
     * Get the country code
     * @return Get the best choice country code for wifi, regardless of if it was set or
     * not.
     * Returns null when there is no country code available.
     */
    @Override
    public String getCountryCode() {
        enforceConnectivityInternalPermission();
        mLog.info("getCountryCode uid=%").c(Binder.getCallingUid()).flush();
        String country = mCountryCode.getCountryCode();
        return country;
    }

    @Override
    public boolean isDualBandSupported() {
        //TODO: Should move towards adding a driver API that checks at runtime
        mLog.info("isDualBandSupported uid=%").c(Binder.getCallingUid()).flush();
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_wifi_dual_band_support);
    }

    /**
     * Return the DHCP-assigned addresses from the last successful DHCP request,
     * if any.
     * @return the DHCP information
     * @deprecated
     */
    @Override
    @Deprecated
    public DhcpInfo getDhcpInfo() {
        enforceAccessPermission();
        mLog.info("getDhcpInfo uid=%").c(Binder.getCallingUid()).flush();
        DhcpResults dhcpResults = mWifiStateMachine.syncGetDhcpResults();

        DhcpInfo info = new DhcpInfo();

        if (dhcpResults.ipAddress != null &&
                dhcpResults.ipAddress.getAddress() instanceof Inet4Address) {
            info.ipAddress = NetworkUtils.inetAddressToInt((Inet4Address) dhcpResults.ipAddress.getAddress());
        }

        if (dhcpResults.gateway != null) {
            info.gateway = NetworkUtils.inetAddressToInt((Inet4Address) dhcpResults.gateway);
        }

        int dnsFound = 0;
        for (InetAddress dns : dhcpResults.dnsServers) {
            if (dns instanceof Inet4Address) {
                if (dnsFound == 0) {
                    info.dns1 = NetworkUtils.inetAddressToInt((Inet4Address)dns);
                } else {
                    info.dns2 = NetworkUtils.inetAddressToInt((Inet4Address)dns);
                }
                if (++dnsFound > 1) break;
            }
        }
        Inet4Address serverAddress = dhcpResults.serverAddress;
        if (serverAddress != null) {
            info.serverAddress = NetworkUtils.inetAddressToInt(serverAddress);
        }
        info.leaseDuration = dhcpResults.leaseDuration;

        return info;
    }

    /**
     * enable TDLS for the local NIC to remote NIC
     * The APPs don't know the remote MAC address to identify NIC though,
     * so we need to do additional work to find it from remote IP address
     */

    class TdlsTaskParams {
        public String remoteIpAddress;
        public boolean enable;
    }

    class TdlsTask extends AsyncTask<TdlsTaskParams, Integer, Integer> {
        @Override
        protected Integer doInBackground(TdlsTaskParams... params) {

            // Retrieve parameters for the call
            TdlsTaskParams param = params[0];
            String remoteIpAddress = param.remoteIpAddress.trim();
            boolean enable = param.enable;

            // Get MAC address of Remote IP
            String macAddress = null;

            BufferedReader reader = null;

            try {
                reader = new BufferedReader(new FileReader("/proc/net/arp"));

                // Skip over the line bearing colum titles
                String line = reader.readLine();

                while ((line = reader.readLine()) != null) {
                    String[] tokens = line.split("[ ]+");
                    if (tokens.length < 6) {
                        continue;
                    }

                    // ARP column format is
                    // Address HWType HWAddress Flags Mask IFace
                    String ip = tokens[0];
                    String mac = tokens[3];

                    if (remoteIpAddress.equals(ip)) {
                        macAddress = mac;
                        break;
                    }
                }

                if (macAddress == null) {
                    Slog.w(TAG, "Did not find remoteAddress {" + remoteIpAddress + "} in " +
                            "/proc/net/arp");
                } else {
                    enableTdlsWithMacAddress(macAddress, enable);
                }

            } catch (FileNotFoundException e) {
                Slog.e(TAG, "Could not open /proc/net/arp to lookup mac address");
            } catch (IOException e) {
                Slog.e(TAG, "Could not read /proc/net/arp to lookup mac address");
            } finally {
                try {
                    if (reader != null) {
                        reader.close();
                    }
                }
                catch (IOException e) {
                    // Do nothing
                }
            }

            return 0;
        }
    }

    @Override
    public void enableTdls(String remoteAddress, boolean enable) {
        if (remoteAddress == null) {
          throw new IllegalArgumentException("remoteAddress cannot be null");
        }
        mLog.info("enableTdls uid=% enable=%").c(Binder.getCallingUid()).c(enable).flush();
        TdlsTaskParams params = new TdlsTaskParams();
        params.remoteIpAddress = remoteAddress;
        params.enable = enable;
        new TdlsTask().execute(params);
    }


    @Override
    public void enableTdlsWithMacAddress(String remoteMacAddress, boolean enable) {
        mLog.info("enableTdlsWithMacAddress uid=% enable=%")
                .c(Binder.getCallingUid())
                .c(enable)
                .flush();
        if (remoteMacAddress == null) {
          throw new IllegalArgumentException("remoteMacAddress cannot be null");
        }

        mWifiStateMachine.enableTdls(remoteMacAddress, enable);
    }

    /**
     * Get a reference to handler. This is used by a client to establish
     * an AsyncChannel communication with WifiService
     */
    @Override
    public Messenger getWifiServiceMessenger() {
        enforceAccessPermission();
        enforceChangePermission();
        mLog.info("getWifiServiceMessenger uid=%").c(Binder.getCallingUid()).flush();
        return new Messenger(mClientHandler);
    }

    /**
     * Disable an ephemeral network, i.e. network that is created thru a WiFi Scorer
     */
    @Override
    public void disableEphemeralNetwork(String SSID) {
        enforceAccessPermission();
        enforceChangePermission();
        mLog.info("disableEphemeralNetwork uid=%").c(Binder.getCallingUid()).flush();
        mWifiStateMachine.disableEphemeralNetwork(SSID);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                mWifiController.sendMessage(CMD_SCREEN_ON);
            } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                mWifiController.sendMessage(CMD_USER_PRESENT);
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mWifiController.sendMessage(CMD_SCREEN_OFF);
            } else if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
                int pluggedType = intent.getIntExtra("plugged", 0);
                mWifiController.sendMessage(CMD_BATTERY_CHANGED, pluggedType, 0, null);
            } else if (action.equals(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                        BluetoothAdapter.STATE_DISCONNECTED);
                mWifiStateMachine.sendBluetoothAdapterStateChange(state);
            } else if (action.equals(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED)) {
                boolean emergencyMode = intent.getBooleanExtra("phoneinECMState", false);
                mWifiController.sendMessage(CMD_EMERGENCY_MODE_CHANGED, emergencyMode ? 1 : 0, 0);
            } else if (action.equals(TelephonyIntents.ACTION_EMERGENCY_CALL_STATE_CHANGED)) {
                boolean inCall = intent.getBooleanExtra(PhoneConstants.PHONE_IN_EMERGENCY_CALL, false);
                mWifiController.sendMessage(CMD_EMERGENCY_CALL_STATE_CHANGED, inCall ? 1 : 0, 0);
            } else if (action.equals(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)) {
                handleIdleModeChanged();
            }
        }
    };

    private boolean startConsentUi(String packageName,
            int callingUid, String intentAction) throws RemoteException {
        if (UserHandle.getAppId(callingUid) == Process.SYSTEM_UID) {
            return false;
        }
        try {
            // Validate the package only if we are going to use it
            ApplicationInfo applicationInfo = mContext.getPackageManager()
                    .getApplicationInfoAsUser(packageName,
                            PackageManager.MATCH_DEBUG_TRIAGED_MISSING,
                            UserHandle.getUserId(callingUid));
            if (applicationInfo.uid != callingUid) {
                throw new SecurityException("Package " + callingUid
                        + " not in uid " + callingUid);
            }

            // Permission review mode, trigger a user prompt
            Intent intent = new Intent(intentAction);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
            mContext.startActivity(intent);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    /**
     * Observes settings changes to scan always mode.
     */
    private void registerForScanModeChange() {
        ContentObserver contentObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                mSettingsStore.handleWifiScanAlwaysAvailableToggled();
                mWifiController.sendMessage(CMD_SCAN_ALWAYS_MODE_CHANGED);
            }
        };
        mFrameworkFacade.registerContentObserver(mContext,
                Settings.Global.getUriFor(Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE),
                false, contentObserver);

    }

    // Monitors settings changes related to background wifi scan throttling.
    private void registerForBackgroundThrottleChanges() {
        mFrameworkFacade.registerContentObserver(
                mContext,
                Settings.Global.getUriFor(
                        Settings.Global.WIFI_SCAN_BACKGROUND_THROTTLE_INTERVAL_MS),
                false,
                new ContentObserver(null) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateBackgroundThrottleInterval();
                    }
                }
        );
        mFrameworkFacade.registerContentObserver(
                mContext,
                Settings.Global.getUriFor(
                        Settings.Global.WIFI_SCAN_BACKGROUND_THROTTLE_PACKAGE_WHITELIST),
                false,
                new ContentObserver(null) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateBackgroundThrottlingWhitelist();
                    }
                }
        );
    }

    private void updateBackgroundThrottleInterval() {
        mBackgroundThrottleInterval = mFrameworkFacade.getLongSetting(
                mContext,
                Settings.Global.WIFI_SCAN_BACKGROUND_THROTTLE_INTERVAL_MS,
                DEFAULT_SCAN_BACKGROUND_THROTTLE_INTERVAL_MS);
    }

    private void updateBackgroundThrottlingWhitelist() {
        String setting = mFrameworkFacade.getStringSetting(
                mContext,
                Settings.Global.WIFI_SCAN_BACKGROUND_THROTTLE_PACKAGE_WHITELIST);
        mBackgroundThrottlePackageWhitelist.clear();
        if (setting != null) {
            mBackgroundThrottlePackageWhitelist.addAll(Arrays.asList(setting.split(",")));
        }
    }

    private void registerForBroadcasts() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_USER_PRESENT);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intentFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        intentFilter.addAction(TelephonyIntents.ACTION_EMERGENCY_CALLBACK_MODE_CHANGED);
        intentFilter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);

        boolean trackEmergencyCallState = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_wifi_turn_off_during_emergency_call);
        if (trackEmergencyCallState) {
            intentFilter.addAction(TelephonyIntents.ACTION_EMERGENCY_CALL_STATE_CHANGED);
        }

        mContext.registerReceiver(mReceiver, intentFilter);
    }

    private void registerForPackageOrUserRemoval() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                switch (intent.getAction()) {
                    case Intent.ACTION_PACKAGE_REMOVED: {
                        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                            return;
                        }
                        int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                        Uri uri = intent.getData();
                        if (uid == -1 || uri == null) {
                            return;
                        }
                        String pkgName = uri.getSchemeSpecificPart();
                        mWifiStateMachine.removeAppConfigs(pkgName, uid);
                        break;
                    }
                    case Intent.ACTION_USER_REMOVED: {
                        int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                        mWifiStateMachine.removeUserConfigs(userHandle);
                        break;
                    }
                }
            }
        }, UserHandle.ALL, intentFilter, null, null);
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        (new WifiShellCommand(mWifiStateMachine)).exec(this, in, out, err, args, callback,
                resultReceiver);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump WifiService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        if (args != null && args.length > 0 && WifiMetrics.PROTO_DUMP_ARG.equals(args[0])) {
            // WifiMetrics proto bytes were requested. Dump only these.
            mWifiStateMachine.updateWifiMetrics();
            mWifiMetrics.dump(fd, pw, args);
        } else if (args != null && args.length > 0 && IpManager.DUMP_ARG.equals(args[0])) {
            // IpManager dump was requested. Pass it along and take no further action.
            String[] ipManagerArgs = new String[args.length - 1];
            System.arraycopy(args, 1, ipManagerArgs, 0, ipManagerArgs.length);
            mWifiStateMachine.dumpIpManager(fd, pw, ipManagerArgs);
        } else if (args != null && args.length > 0 && WifiScoreReport.DUMP_ARG.equals(args[0])) {
            WifiScoreReport wifiScoreReport = mWifiStateMachine.getWifiScoreReport();
            if (wifiScoreReport != null) wifiScoreReport.dump(fd, pw, args);
        } else {
            pw.println("Wi-Fi is " + mWifiStateMachine.syncGetWifiStateByName());
            pw.println("Stay-awake conditions: " +
                    mFacade.getIntegerSetting(mContext,
                            Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0));
            pw.println("mInIdleMode " + mInIdleMode);
            pw.println("mScanPending " + mScanPending);
            mWifiController.dump(fd, pw, args);
            mSettingsStore.dump(fd, pw, args);
            mTrafficPoller.dump(fd, pw, args);
            pw.println();
            pw.println("Locks held:");
            mWifiLockManager.dump(pw);
            pw.println();
            mWifiMulticastLockManager.dump(pw);
            pw.println();
            mWifiStateMachine.dump(fd, pw, args);
            pw.println();
            mWifiStateMachine.updateWifiMetrics();
            mWifiMetrics.dump(fd, pw, args);
            pw.println();
            mWifiBackupRestore.dump(fd, pw, args);
            pw.println();
            WifiScoreReport wifiScoreReport = mWifiStateMachine.getWifiScoreReport();
            if (wifiScoreReport != null) {
                pw.println("WifiScoreReport:");
                wifiScoreReport.dump(fd, pw, args);
            }
            pw.println();
        }
    }

    @Override
    public boolean acquireWifiLock(IBinder binder, int lockMode, String tag, WorkSource ws) {
        mLog.info("acquireWifiLock uid=% lockMode=%")
                .c(Binder.getCallingUid())
                .c(lockMode).flush();
        if (mWifiLockManager.acquireWifiLock(lockMode, tag, binder, ws)) {
            mWifiController.sendMessage(CMD_LOCKS_CHANGED);
            return true;
        }
        return false;
    }

    @Override
    public void updateWifiLockWorkSource(IBinder binder, WorkSource ws) {
        mLog.info("updateWifiLockWorkSource uid=%").c(Binder.getCallingUid()).flush();
        mWifiLockManager.updateWifiLockWorkSource(binder, ws);
    }

    @Override
    public boolean releaseWifiLock(IBinder binder) {
        mLog.info("releaseWifiLock uid=%").c(Binder.getCallingUid()).flush();
        if (mWifiLockManager.releaseWifiLock(binder)) {
            mWifiController.sendMessage(CMD_LOCKS_CHANGED);
            return true;
        }
        return false;
    }

    @Override
    public void initializeMulticastFiltering() {
        enforceMulticastChangePermission();
        mLog.info("initializeMulticastFiltering uid=%").c(Binder.getCallingUid()).flush();
        mWifiMulticastLockManager.initializeFiltering();
    }

    @Override
    public void acquireMulticastLock(IBinder binder, String tag) {
        enforceMulticastChangePermission();
        mLog.info("acquireMulticastLock uid=%").c(Binder.getCallingUid()).flush();
        mWifiMulticastLockManager.acquireLock(binder, tag);
    }

    @Override
    public void releaseMulticastLock() {
        enforceMulticastChangePermission();
        mLog.info("releaseMulticastLock uid=%").c(Binder.getCallingUid()).flush();
        mWifiMulticastLockManager.releaseLock();
    }

    @Override
    public boolean isMulticastEnabled() {
        enforceAccessPermission();
        mLog.info("isMulticastEnabled uid=%").c(Binder.getCallingUid()).flush();
        return mWifiMulticastLockManager.isMulticastEnabled();
    }

    @Override
    public void enableVerboseLogging(int verbose) {
        enforceAccessPermission();
        mLog.info("enableVerboseLogging uid=% verbose=%")
                .c(Binder.getCallingUid())
                .c(verbose).flush();
        mFacade.setIntegerSetting(
                mContext, Settings.Global.WIFI_VERBOSE_LOGGING_ENABLED, verbose);
        enableVerboseLoggingInternal(verbose);
    }

    void enableVerboseLoggingInternal(int verbose) {
        mWifiStateMachine.enableVerboseLogging(verbose);
        mWifiLockManager.enableVerboseLogging(verbose);
        mWifiMulticastLockManager.enableVerboseLogging(verbose);
        mWifiInjector.getWifiLastResortWatchdog().enableVerboseLogging(verbose);
        mWifiInjector.getWifiBackupRestore().enableVerboseLogging(verbose);
        LogcatLog.enableVerboseLogging(verbose);
    }

    @Override
    public int getVerboseLoggingLevel() {
        enforceAccessPermission();
        mLog.info("getVerboseLoggingLevel uid=%").c(Binder.getCallingUid()).flush();
        return mFacade.getIntegerSetting(
                mContext, Settings.Global.WIFI_VERBOSE_LOGGING_ENABLED, 0);
    }

    @Override
    public void enableAggressiveHandover(int enabled) {
        enforceAccessPermission();
        mLog.info("enableAggressiveHandover uid=% enabled=%")
            .c(Binder.getCallingUid())
            .c(enabled)
            .flush();
        mWifiStateMachine.enableAggressiveHandover(enabled);
    }

    @Override
    public int getAggressiveHandover() {
        enforceAccessPermission();
        mLog.info("getAggressiveHandover uid=%").c(Binder.getCallingUid()).flush();
        return mWifiStateMachine.getAggressiveHandover();
    }

    @Override
    public void setAllowScansWithTraffic(int enabled) {
        enforceAccessPermission();
        mLog.info("setAllowScansWithTraffic uid=% enabled=%")
                .c(Binder.getCallingUid())
                .c(enabled).flush();
        mWifiStateMachine.setAllowScansWithTraffic(enabled);
    }

    @Override
    public int getAllowScansWithTraffic() {
        enforceAccessPermission();
        mLog.info("getAllowScansWithTraffic uid=%").c(Binder.getCallingUid()).flush();
        return mWifiStateMachine.getAllowScansWithTraffic();
    }

    @Override
    public boolean setEnableAutoJoinWhenAssociated(boolean enabled) {
        enforceChangePermission();
        mLog.info("setEnableAutoJoinWhenAssociated uid=% enabled=%")
                .c(Binder.getCallingUid())
                .c(enabled).flush();
        return mWifiStateMachine.setEnableAutoJoinWhenAssociated(enabled);
    }

    @Override
    public boolean getEnableAutoJoinWhenAssociated() {
        enforceAccessPermission();
        mLog.info("getEnableAutoJoinWhenAssociated uid=%").c(Binder.getCallingUid()).flush();
        return mWifiStateMachine.getEnableAutoJoinWhenAssociated();
    }

    /* Return the Wifi Connection statistics object */
    @Override
    public WifiConnectionStatistics getConnectionStatistics() {
        enforceAccessPermission();
        enforceReadCredentialPermission();
        mLog.info("getConnectionStatistics uid=%").c(Binder.getCallingUid()).flush();
        if (mWifiStateMachineChannel != null) {
            return mWifiStateMachine.syncGetConnectionStatistics(mWifiStateMachineChannel);
        } else {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return null;
        }
    }

    @Override
    public void factoryReset() {
        enforceConnectivityInternalPermission();
        mLog.info("factoryReset uid=%").c(Binder.getCallingUid()).flush();
        if (mUserManager.hasUserRestriction(UserManager.DISALLOW_NETWORK_RESET)) {
            return;
        }

        if (!mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING)) {
            // Turn mobile hotspot off - will also clear any registered LOHS requests when it is
            // shut down
            stopSoftApInternal();
        }

        if (!mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_WIFI)) {
            // Enable wifi
            try {
                setWifiEnabled(mContext.getOpPackageName(), true);
            } catch (RemoteException e) {
                /* ignore - local call */
            }
            // Delete all Wifi SSIDs
            if (mWifiStateMachineChannel != null) {
                List<WifiConfiguration> networks = mWifiStateMachine.syncGetConfiguredNetworks(
                        Binder.getCallingUid(), mWifiStateMachineChannel);
                if (networks != null) {
                    for (WifiConfiguration config : networks) {
                        removeNetwork(config.networkId);
                    }
                    saveConfiguration();
                }
            }
        }
    }

    /* private methods */
    static boolean logAndReturnFalse(String s) {
        Log.d(TAG, s);
        return false;
    }

    public static boolean isValid(WifiConfiguration config) {
        String validity = checkValidity(config);
        return validity == null || logAndReturnFalse(validity);
    }

    public static String checkValidity(WifiConfiguration config) {
        if (config.allowedKeyManagement == null)
            return "allowed kmgmt";

        if (config.allowedKeyManagement.cardinality() > 1) {
            if (config.allowedKeyManagement.cardinality() != 2) {
                return "cardinality != 2";
            }
            if (!config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)) {
                return "not WPA_EAP";
            }
            if ((!config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X))
                    && (!config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK))) {
                return "not PSK or 8021X";
            }
        }
        if (config.getIpAssignment() == IpConfiguration.IpAssignment.STATIC) {
            StaticIpConfiguration staticIpConf = config.getStaticIpConfiguration();
            if (staticIpConf == null) {
                return "null StaticIpConfiguration";
            }
            if (staticIpConf.ipAddress == null) {
                return "null static ip Address";
            }
        }
        return null;
    }

    @Override
    public Network getCurrentNetwork() {
        enforceAccessPermission();
        mLog.info("getCurrentNetwork uid=%").c(Binder.getCallingUid()).flush();
        return mWifiStateMachine.getCurrentNetwork();
    }

    public static String toHexString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('\'').append(s).append('\'');
        for (int n = 0; n < s.length(); n++) {
            sb.append(String.format(" %02x", s.charAt(n) & 0xffff));
        }
        return sb.toString();
    }

    public void hideCertFromUnaffiliatedUsers(String alias) {
        mCertManager.hideCertFromUnaffiliatedUsers(alias);
    }

    public String[] listClientCertsForCurrentUser() {
        return mCertManager.listClientCertsForCurrentUser();
    }

    /**
     * Enable/disable WifiConnectivityManager at runtime
     *
     * @param enabled true-enable; false-disable
     */
    @Override
    public void enableWifiConnectivityManager(boolean enabled) {
        enforceConnectivityInternalPermission();
        mLog.info("enableWifiConnectivityManager uid=% enabled=%")
                .c(Binder.getCallingUid())
                .c(enabled).flush();
        mWifiStateMachine.enableWifiConnectivityManager(enabled);
    }

    /**
     * Retrieve the data to be backed to save the current state.
     *
     * @return  Raw byte stream of the data to be backed up.
     */
    @Override
    public byte[] retrieveBackupData() {
        enforceNetworkSettingsPermission();
        mLog.info("retrieveBackupData uid=%").c(Binder.getCallingUid()).flush();
        if (mWifiStateMachineChannel == null) {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return null;
        }

        Slog.d(TAG, "Retrieving backup data");
        List<WifiConfiguration> wifiConfigurations =
                mWifiStateMachine.syncGetPrivilegedConfiguredNetwork(mWifiStateMachineChannel);
        byte[] backupData =
                mWifiBackupRestore.retrieveBackupDataFromConfigurations(wifiConfigurations);
        Slog.d(TAG, "Retrieved backup data");
        return backupData;
    }

    /**
     * Helper method to restore networks retrieved from backup data.
     *
     * @param configurations list of WifiConfiguration objects parsed from the backup data.
     */
    private void restoreNetworks(List<WifiConfiguration> configurations) {
        if (configurations == null) {
            Slog.e(TAG, "Backup data parse failed");
            return;
        }
        for (WifiConfiguration configuration : configurations) {
            int networkId = mWifiStateMachine.syncAddOrUpdateNetwork(
                    mWifiStateMachineChannel, configuration);
            if (networkId == WifiConfiguration.INVALID_NETWORK_ID) {
                Slog.e(TAG, "Restore network failed: " + configuration.configKey());
                continue;
            }
            // Enable all networks restored.
            mWifiStateMachine.syncEnableNetwork(mWifiStateMachineChannel, networkId, false);
        }
    }

    /**
     * Restore state from the backed up data.
     *
     * @param data Raw byte stream of the backed up data.
     */
    @Override
    public void restoreBackupData(byte[] data) {
        enforceNetworkSettingsPermission();
        mLog.info("restoreBackupData uid=%").c(Binder.getCallingUid()).flush();
        if (mWifiStateMachineChannel == null) {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return;
        }

        Slog.d(TAG, "Restoring backup data");
        List<WifiConfiguration> wifiConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromBackupData(data);
        restoreNetworks(wifiConfigurations);
        Slog.d(TAG, "Restored backup data");
    }

    /**
     * Restore state from the older supplicant back up data.
     * The old backup data was essentially a backup of wpa_supplicant.conf & ipconfig.txt file.
     *
     * @param supplicantData Raw byte stream of wpa_supplicant.conf
     * @param ipConfigData Raw byte stream of ipconfig.txt
     */
    public void restoreSupplicantBackupData(byte[] supplicantData, byte[] ipConfigData) {
        enforceNetworkSettingsPermission();
        mLog.trace("restoreSupplicantBackupData uid=%").c(Binder.getCallingUid()).flush();
        if (mWifiStateMachineChannel == null) {
            Slog.e(TAG, "mWifiStateMachineChannel is not initialized");
            return;
        }

        Slog.d(TAG, "Restoring supplicant backup data");
        List<WifiConfiguration> wifiConfigurations =
                mWifiBackupRestore.retrieveConfigurationsFromSupplicantBackupData(
                        supplicantData, ipConfigData);
        restoreNetworks(wifiConfigurations);
        Slog.d(TAG, "Restored supplicant backup data");
    }
}
