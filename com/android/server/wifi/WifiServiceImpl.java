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

import static android.app.AppOpsManager.MODE_ALLOWED;
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
import static com.android.server.wifi.WifiController.CMD_EMERGENCY_CALL_STATE_CHANGED;
import static com.android.server.wifi.WifiController.CMD_EMERGENCY_MODE_CHANGED;
import static com.android.server.wifi.WifiController.CMD_SCAN_ALWAYS_MODE_CHANGED;
import static com.android.server.wifi.WifiController.CMD_SET_AP;
import static com.android.server.wifi.WifiController.CMD_USER_PRESENT;
import static com.android.server.wifi.WifiController.CMD_WIFI_TOGGLED;

import android.Manifest;
import android.annotation.CheckResult;
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
import android.net.Network;
import android.net.NetworkUtils;
import android.net.Uri;
import android.net.ip.IpClient;
import android.net.wifi.ISoftApCallback;
import android.net.wifi.IWifiManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiActivityEnergyInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback;
import android.net.wifi.WifiSsid;
import android.net.wifi.hotspot2.IProvisioningCallback;
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
import android.util.Log;
import android.util.MutableInt;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.PowerProfile;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.AsyncChannel;
import com.android.server.wifi.hotspot2.PasspointProvider;
import com.android.server.wifi.util.GeneralUtil.Mutable;
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
import java.util.Iterator;
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
    private static final boolean VDBG = false;

    // Default scan background throttling interval if not overriden in settings
    private static final long DEFAULT_SCAN_BACKGROUND_THROTTLE_INTERVAL_MS = 30 * 60 * 1000;

    // Apps with importance higher than this value is considered as background app.
    private static final int BACKGROUND_IMPORTANCE_CUTOFF =
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;

    // Max wait time for posting blocking runnables
    private static final int RUN_WITH_SCISSORS_TIMEOUT_MILLIS = 4000;

    final WifiStateMachine mWifiStateMachine;
    final WifiStateMachinePrime mWifiStateMachinePrime;
    final ScanRequestProxy mScanRequestProxy;

    private final Context mContext;
    private final FrameworkFacade mFacade;
    private final Clock mClock;

    private final PowerManager mPowerManager;
    private final AppOpsManager mAppOps;
    private final UserManager mUserManager;
    private final ActivityManager mActivityManager;
    private final WifiCountryCode mCountryCode;
    // Debug counter tracking scan requests sent by WifiManager
    private int scanRequestCounter = 0;

    /* Polls traffic stats and notifies clients */
    private WifiTrafficPoller mTrafficPoller;
    /* Tracks the persisted states for wi-fi & airplane mode */
    final WifiSettingsStore mSettingsStore;
    /* Logs connection events and some general router and scan stats */
    private final WifiMetrics mWifiMetrics;

    private final WifiInjector mWifiInjector;
    /* Backup/Restore Module */
    private final WifiBackupRestore mWifiBackupRestore;

    private WifiLog mLog;
    /**
     * Verbose logging flag. Toggled by developer options.
     */
    private boolean mVerboseLoggingEnabled = false;

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

    /* Limit on number of registered soft AP callbacks to track and prevent potential memory leak */
    private static final int NUM_SOFT_AP_CALLBACKS_WARN_LIMIT = 10;
    private static final int NUM_SOFT_AP_CALLBACKS_WTF_LIMIT = 20;
    private final HashMap<Integer, ISoftApCallback> mRegisteredSoftApCallbacks;

    /**
     * One of:  {@link WifiManager#WIFI_AP_STATE_DISABLED},
     *          {@link WifiManager#WIFI_AP_STATE_DISABLING},
     *          {@link WifiManager#WIFI_AP_STATE_ENABLED},
     *          {@link WifiManager#WIFI_AP_STATE_ENABLING},
     *          {@link WifiManager#WIFI_AP_STATE_FAILED}
     *
     * Access/maintenance MUST be done on the wifi service thread
     */
    // TODO: (b/71714381) Remove mWifiApState and broadcast mechanism, keep mSoftApState as the only
    //       field to store soft AP state. Then rename mSoftApState and mSoftApNumClients to
    //       mWifiApState and mWifiApNumClients, to match the constants (i.e. WIFI_AP_STATE_*)
    private int mWifiApState = WifiManager.WIFI_AP_STATE_DISABLED;
    private int mSoftApState = WifiManager.WIFI_AP_STATE_DISABLED;
    private int mSoftApNumClients = 0;

    /**
     * Power profile
     */
    PowerProfile mPowerProfile;

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
                        Slog.d(TAG, "New client listening to asynchronous messages");
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
                        Slog.w(TAG, "Send failed, client connection lost");
                    } else {
                        Slog.w(TAG, "Client connection lost with reason: " + msg.arg1);
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
                                + " config=" + config
                                + " uid=" + msg.sendingUid
                                + " name="
                                + mContext.getPackageManager().getNameForUid(msg.sendingUid));
                        if (config != null) {
                            /* Command is forwarded to state machine */
                            mWifiStateMachine.sendMessage(Message.obtain(msg));
                        } else if (config == null
                                && networkId != WifiConfiguration.INVALID_NETWORK_ID) {
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
                                + " config=" + config
                                + " uid=" + msg.sendingUid
                                + " name="
                                + mContext.getPackageManager().getNameForUid(msg.sendingUid));
                        if (config != null) {
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
                        // WPS support is deprecated, return an error
                        replyFailed(msg, WifiManager.WPS_FAILED, WifiManager.ERROR);
                    }
                    break;
                case WifiManager.CANCEL_WPS:
                    if (checkChangePermissionAndReplyIfNotAuthorized(
                            msg, WifiManager.CANCEL_WPS_FAILED)) {
                        // WPS support is deprecated, return an error
                        replyFailed(msg, WifiManager.CANCEL_WPS_FAILED, WifiManager.ERROR);
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

    private WifiApConfigStore mWifiApConfigStore;

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
        mWifiStateMachinePrime = mWifiInjector.getWifiStateMachinePrime();
        mWifiStateMachine.enableRssiPolling(true);
        mScanRequestProxy = mWifiInjector.getScanRequestProxy();
        mSettingsStore = mWifiInjector.getWifiSettingsStore();
        mPowerManager = mContext.getSystemService(PowerManager.class);
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mWifiLockManager = mWifiInjector.getWifiLockManager();
        mWifiMulticastLockManager = mWifiInjector.getWifiMulticastLockManager();
        HandlerThread wifiServiceHandlerThread = mWifiInjector.getWifiServiceHandlerThread();
        mClientHandler = new ClientHandler(TAG, wifiServiceHandlerThread.getLooper());
        mWifiStateMachineHandler = new WifiStateMachineHandler(TAG,
                wifiServiceHandlerThread.getLooper(), asyncChannel);
        mWifiController = mWifiInjector.getWifiController();
        mWifiBackupRestore = mWifiInjector.getWifiBackupRestore();
        mWifiApConfigStore = mWifiInjector.getWifiApConfigStore();
        mPermissionReviewRequired = Build.PERMISSIONS_REVIEW_REQUIRED
                || context.getResources().getBoolean(
                com.android.internal.R.bool.config_permissionReviewRequired);
        mWifiPermissionsUtil = mWifiInjector.getWifiPermissionsUtil();
        mLog = mWifiInjector.makeLog(TAG);
        mFrameworkFacade = wifiInjector.getFrameworkFacade();
        mIfaceIpModes = new ConcurrentHashMap<>();
        mLocalOnlyHotspotRequests = new HashMap<>();
        enableVerboseLoggingInternal(getVerboseLoggingLevel());
        mRegisteredSoftApCallbacks = new HashMap<>();

        mWifiInjector.getWifiStateMachinePrime().registerSoftApCallback(new SoftApCallbackImpl());
        mPowerProfile = mWifiInjector.getPowerProfile();
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
     * See {@link android.net.wifi.WifiManager#startScan}
     *
     * @param packageName Package name of the app that requests wifi scan.
     */
    @Override
    public boolean startScan(String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }

        int callingUid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        mLog.info("startScan uid=%").c(callingUid).flush();
        synchronized (this) {
            if (mInIdleMode) {
                // Need to send an immediate scan result broadcast in case the
                // caller is waiting for a result ..

                // TODO: investigate if the logic to cancel scans when idle can move to
                // WifiScanningServiceImpl.  This will 1 - clean up WifiServiceImpl and 2 -
                // avoid plumbing an awkward path to report a cancelled/failed scan.  This will
                // be sent directly until b/31398592 is fixed.
                sendFailedScanBroadcast();
                mScanPending = true;
                return false;
            }
        }
        try {
            mWifiPermissionsUtil.enforceCanAccessScanResults(packageName, callingUid);
            Mutable<Boolean> scanSuccess = new Mutable<>();
            boolean runWithScissorsSuccess = mWifiInjector.getWifiStateMachineHandler()
                    .runWithScissors(() -> {
                        scanSuccess.value = mScanRequestProxy.startScan(callingUid, packageName);
                    }, RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
            if (!runWithScissorsSuccess) {
                Log.e(TAG, "Failed to post runnable to start scan");
                sendFailedScanBroadcast();
                return false;
            }
            if (!scanSuccess.value) {
                Log.e(TAG, "Failed to start scan");
                return false;
            }
        } catch (SecurityException e) {
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return true;
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

    /**
     * WPS support in Client mode is deprecated.  Return null.
     */
    @Override
    public String getCurrentNetworkWpsNfcConfigurationToken() {
        // while CLs are in flight, return null here, will be removed (b/72423090)
        enforceConnectivityInternalPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("getCurrentNetworkWpsNfcConfigurationToken uid=%")
                    .c(Binder.getCallingUid()).flush();
        }
        return null;
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
            // A security check of the caller's identity was made when the request arrived via
            // Binder. Now we'll pass the current process's identity to startScan().
            startScan(mContext.getOpPackageName());
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

    /**
     * Checks whether the caller can change the wifi state.
     * Possible results:
     * 1. Operation is allowed. No exception thrown, and AppOpsManager.MODE_ALLOWED returned.
     * 2. Operation is not allowed, and caller must be told about this. SecurityException is thrown.
     * 3. Operation is not allowed, and caller must not be told about this (i.e. must silently
     * ignore the operation). No exception is thrown, and AppOpsManager.MODE_IGNORED returned.
     */
    @CheckResult
    private int enforceChangePermission(String callingPackage) {
        if (checkNetworkSettingsPermission(Binder.getCallingPid(), Binder.getCallingUid())) {
            return MODE_ALLOWED;
        }
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE,
                "WifiService");

        return mAppOps.noteOp(
                AppOpsManager.OPSTR_CHANGE_WIFI_STATE, Binder.getCallingUid(), callingPackage);
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
     * Check if the caller must still pass permission check or if the caller is exempted
     * from the consent UI via the MANAGE_WIFI_WHEN_PERMISSION_REVIEW_REQUIRED check.
     *
     * Commands from some callers may be exempted from triggering the consent UI when
     * enabling wifi. This exemption is checked via the MANAGE_WIFI_WHEN_PERMISSION_REVIEW_REQUIRED
     * and allows calls to skip the consent UI where it may otherwise be required.
     *
     * @hide
     */
    private boolean checkWifiPermissionWhenPermissionReviewRequired() {
        if (!mPermissionReviewRequired) {
            return false;
        }
        int result = mContext.checkCallingPermission(
                android.Manifest.permission.MANAGE_WIFI_WHEN_PERMISSION_REVIEW_REQUIRED);
        return result == PackageManager.PERMISSION_GRANTED;
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
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }

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
        boolean apEnabled = mWifiApState == WifiManager.WIFI_AP_STATE_ENABLED;

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
        if (mVerboseLoggingEnabled) {
            mLog.info("getWifiEnabledState uid=%").c(Binder.getCallingUid()).flush();
        }
        return mWifiStateMachine.syncGetWifiState();
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
        if (mVerboseLoggingEnabled) {
            mLog.info("getWifiApEnabledState uid=%").c(Binder.getCallingUid()).flush();
        }

        // hand off work to our handler thread
        MutableInt apState = new MutableInt(WifiManager.WIFI_AP_STATE_DISABLED);
        mClientHandler.runWithScissors(() -> {
            apState.value = mWifiApState;
        }, RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
        return apState.value;
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
        mLog.info("updateInterfaceIpState uid=%").c(Binder.getCallingUid()).flush();

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
                    Slog.d(TAG, "IP mode config error - need to clean up");
                    if (mLocalOnlyHotspotRequests.isEmpty()) {
                        Slog.d(TAG, "no LOHS requests, stop softap");
                        stopSoftAp();
                    } else {
                        Slog.d(TAG, "we have LOHS requests, clean them up");
                        // there was an error setting up the hotspot...  trigger onFailed for the
                        // registered LOHS requestors
                        sendHotspotFailedMessageToAllLOHSRequestInfoEntriesLocked(
                                LocalOnlyHotspotCallback.ERROR_GENERIC);
                    }
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
        if (wifiConfig == null || WifiApConfigStore.validateApWifiConfiguration(wifiConfig)) {
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
                        + " Registered LOHS callers will be updated when softap stopped.").flush();
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
     * Callback to use with WifiStateMachine to receive events from WifiStateMachine
     *
     * @hide
     */
    private final class SoftApCallbackImpl implements WifiManager.SoftApCallback {
        /**
         * Called when soft AP state changes.
         *
         * @param state new new AP state. One of {@link #WIFI_AP_STATE_DISABLED},
         *        {@link #WIFI_AP_STATE_DISABLING}, {@link #WIFI_AP_STATE_ENABLED},
         *        {@link #WIFI_AP_STATE_ENABLING}, {@link #WIFI_AP_STATE_FAILED}
         * @param failureReason reason when in failed state. One of
         *        {@link #SAP_START_FAILURE_GENERAL}, {@link #SAP_START_FAILURE_NO_CHANNEL}
         */
        @Override
        public void onStateChanged(int state, int failureReason) {
            mSoftApState = state;

            Iterator<ISoftApCallback> iterator = mRegisteredSoftApCallbacks.values().iterator();
            while (iterator.hasNext()) {
                ISoftApCallback callback = iterator.next();
                try {
                    callback.onStateChanged(state, failureReason);
                } catch (RemoteException e) {
                    Log.e(TAG, "onStateChanged: remote exception -- " + e);
                    iterator.remove();
                }
            }
        }

        /**
         * Called when number of connected clients to soft AP changes.
         *
         * @param numClients number of connected clients to soft AP
         */
        @Override
        public void onNumClientsChanged(int numClients) {
            mSoftApNumClients = numClients;

            Iterator<ISoftApCallback> iterator = mRegisteredSoftApCallbacks.values().iterator();
            while (iterator.hasNext()) {
                ISoftApCallback callback = iterator.next();
                try {
                    callback.onNumClientsChanged(numClients);
                } catch (RemoteException e) {
                    Log.e(TAG, "onNumClientsChanged: remote exception -- " + e);
                    iterator.remove();
                }
            }
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#registerSoftApCallback(SoftApCallback, Handler)}
     *
     * @param binder IBinder instance to allow cleanup if the app dies
     * @param callback Soft AP callback to register
     * @param callbackIdentifier Unique ID of the registering callback. This ID will be used to
     *        unregister the callback. See {@link unregisterSoftApCallback(int)}
     *
     * @throws SecurityException if the caller does not have permission to register a callback
     * @throws RemoteException if remote exception happens
     * @throws IllegalArgumentException if the arguments are null or invalid
     */
    @Override
    public void registerSoftApCallback(IBinder binder, ISoftApCallback callback,
            int callbackIdentifier) {
        // verify arguments
        if (binder == null) {
            throw new IllegalArgumentException("Binder must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }

        enforceNetworkSettingsPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("registerSoftApCallback uid=%").c(Binder.getCallingUid()).flush();
        }

        // register for binder death
        IBinder.DeathRecipient dr = new IBinder.DeathRecipient() {
            @Override
            public void binderDied() {
                binder.unlinkToDeath(this, 0);
                mClientHandler.post(() -> {
                    mRegisteredSoftApCallbacks.remove(callbackIdentifier);
                });
            }
        };
        try {
            binder.linkToDeath(dr, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Error on linkToDeath - " + e);
            return;
        }

        // post operation to handler thread
        mClientHandler.post(() -> {
            mRegisteredSoftApCallbacks.put(callbackIdentifier, callback);

            if (mRegisteredSoftApCallbacks.size() > NUM_SOFT_AP_CALLBACKS_WTF_LIMIT) {
                Log.wtf(TAG, "Too many soft AP callbacks: " + mRegisteredSoftApCallbacks.size());
            } else if (mRegisteredSoftApCallbacks.size() > NUM_SOFT_AP_CALLBACKS_WARN_LIMIT) {
                Log.w(TAG, "Too many soft AP callbacks: " + mRegisteredSoftApCallbacks.size());
            }

            // Update the client about the current state immediately after registering the callback
            try {
                callback.onStateChanged(mSoftApState, 0);
                callback.onNumClientsChanged(mSoftApNumClients);
            } catch (RemoteException e) {
                Log.e(TAG, "registerSoftApCallback: remote exception -- " + e);
            }

        });
    }

    /**
     * see {@link android.net.wifi.WifiManager#unregisterSoftApCallback(SoftApCallback)}
     *
     * @param callbackIdentifier Unique ID of the callback to be unregistered.
     *
     * @throws SecurityException if the caller does not have permission to register a callback
     */
    @Override
    public void unregisterSoftApCallback(int callbackIdentifier) {

        enforceNetworkSettingsPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("unregisterSoftApCallback uid=%").c(Binder.getCallingUid()).flush();
        }

        // post operation to handler thread
        mClientHandler.post(() -> {
            mRegisteredSoftApCallbacks.remove(callbackIdentifier);
        });
    }

    /**
     * Private method to handle SoftAp state changes
     *
     * <p> MUST be called from the WifiStateMachine thread.
     */
    private void handleWifiApStateChange(
            int currentState, int previousState, int errorCode, String ifaceName, int mode) {
        // The AP state update from WifiStateMachine for softap
        Slog.d(TAG, "handleWifiApStateChange: currentState=" + currentState
                + " previousState=" + previousState + " errorCode= " + errorCode
                + " ifaceName=" + ifaceName + " mode=" + mode);

        // update the tracking ap state variable
        mWifiApState = currentState;

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
    @GuardedBy("mLocalOnlyHotspotRequests")
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
    @GuardedBy("mLocalOnlyHotspotRequests")
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
    @GuardedBy("mLocalOnlyHotspotRequests")
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

        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return LocalOnlyHotspotCallback.ERROR_GENERIC;
        }
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
            mLog.warn("RemoteException during isAppForeground when calling startLOHS").flush();
            return LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE;
        }

        mLog.info("startLocalOnlyHotspot uid=% pid=%").c(uid).c(pid).flush();

        synchronized (mLocalOnlyHotspotRequests) {
            // check if we are currently tethering
            if (mIfaceIpModes.contains(WifiManager.IFACE_IP_MODE_TETHERED)) {
                // Tethering is enabled, cannot start LocalOnlyHotspot
                mLog.info("Cannot start localOnlyHotspot when WiFi Tethering is active.").flush();
                return LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE;
            }

            // does this caller already have a request?
            LocalOnlyHotspotRequestInfo request = mLocalOnlyHotspotRequests.get(pid);
            if (request != null) {
                mLog.trace("caller already has an active request").flush();
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
                    mLog.trace("LOHS already up, trigger onStarted callback").flush();
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
        // don't do a permission check here. if the app's permission to change the wifi state is
        // revoked, we still want them to be able to stop a previously created hotspot (otherwise
        // it could cost the user money). When the app created the hotspot, its permission was
        // checked.
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
                mLog.trace("LocalOnlyHotspotRequestInfo not found to remove").flush();
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

        // hand off work to the WSM handler thread to sync work between calls and SoftApManager
        // starting up softap
        final Mutable<WifiConfiguration> config = new Mutable();
        boolean success = mWifiInjector.getWifiStateMachineHandler().runWithScissors(() -> {
            config.value = mWifiApConfigStore.getApConfiguration();
        }, RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
        if (success) {
            return config.value;
        }
        Log.e(TAG, "Failed to post runnable to fetch ap config");
        return new WifiConfiguration();
    }

    /**
     * see {@link WifiManager#setWifiApConfiguration(WifiConfiguration)}
     * @param wifiConfig WifiConfiguration details for soft access point
     * @return boolean indicating success or failure of the operation
     * @throws SecurityException if the caller does not have permission to write the softap config
     */
    @Override
    public boolean setWifiApConfiguration(WifiConfiguration wifiConfig, String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
        int uid = Binder.getCallingUid();
        // only allow Settings UI to write the stored SoftApConfig
        if (!mWifiPermissionsUtil.checkConfigOverridePermission(uid)) {
            // random apps should not be allowed to read the user specified config
            throw new SecurityException("App not allowed to read or update stored WiFi AP config "
                    + "(uid = " + uid + ")");
        }
        mLog.info("setWifiApConfiguration uid=%").c(uid).flush();
        if (wifiConfig == null)
            return false;
        if (WifiApConfigStore.validateApWifiConfiguration(wifiConfig)) {
            mWifiStateMachineHandler.post(() -> {
                mWifiApConfigStore.setApConfiguration(wifiConfig);
            });
            return true;
        } else {
            Slog.e(TAG, "Invalid WifiConfiguration");
            return false;
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#isScanAlwaysAvailable()}
     */
    @Override
    public boolean isScanAlwaysAvailable() {
        enforceAccessPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("isScanAlwaysAvailable uid=%").c(Binder.getCallingUid()).flush();
        }
        return mSettingsStore.isScanAlwaysAvailable();
    }

    /**
     * see {@link android.net.wifi.WifiManager#disconnect()}
     */
    @Override
    public void disconnect(String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return;
        }
        mLog.info("disconnect uid=%").c(Binder.getCallingUid()).flush();
        mWifiStateMachine.disconnectCommand();
    }

    /**
     * see {@link android.net.wifi.WifiManager#reconnect()}
     */
    @Override
    public void reconnect(String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return;
        }
        mLog.info("reconnect uid=%").c(Binder.getCallingUid()).flush();
        mWifiStateMachine.reconnectCommand(new WorkSource(Binder.getCallingUid()));
    }

    /**
     * see {@link android.net.wifi.WifiManager#reassociate()}
     */
    @Override
    public void reassociate(String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return;
        }
        mLog.info("reassociate uid=%").c(Binder.getCallingUid()).flush();
        mWifiStateMachine.reassociateCommand();
    }

    /**
     * see {@link android.net.wifi.WifiManager#getSupportedFeatures}
     */
    @Override
    public int getSupportedFeatures() {
        enforceAccessPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("getSupportedFeatures uid=%").c(Binder.getCallingUid()).flush();
        }
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
        if (mVerboseLoggingEnabled) {
            mLog.info("requestActivityInfo uid=%").c(Binder.getCallingUid()).flush();
        }
        bundle.putParcelable(BatteryStats.RESULT_RECEIVER_CONTROLLER_KEY, reportActivityInfo());
        result.send(0, bundle);
    }

    /**
     * see {@link android.net.wifi.WifiManager#getControllerActivityEnergyInfo(int)}
     */
    @Override
    public WifiActivityEnergyInfo reportActivityInfo() {
        enforceAccessPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("reportActivityInfo uid=%").c(Binder.getCallingUid()).flush();
        }
        if ((getSupportedFeatures() & WifiManager.WIFI_FEATURE_LINK_LAYER_STATS) == 0) {
            return null;
        }
        WifiLinkLayerStats stats;
        WifiActivityEnergyInfo energyInfo = null;
        if (mWifiStateMachineChannel != null) {
            stats = mWifiStateMachine.syncGetLinkLayerStats(mWifiStateMachineChannel);
            if (stats != null) {
                final double rxIdleCurrent = mPowerProfile.getAveragePower(
                    PowerProfile.POWER_WIFI_CONTROLLER_IDLE);
                final double rxCurrent = mPowerProfile.getAveragePower(
                    PowerProfile.POWER_WIFI_CONTROLLER_RX);
                final double txCurrent = mPowerProfile.getAveragePower(
                    PowerProfile.POWER_WIFI_CONTROLLER_TX);
                final double voltage = mPowerProfile.getAveragePower(
                    PowerProfile.POWER_WIFI_CONTROLLER_OPERATING_VOLTAGE) / 1000.0;
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
                        stats.rx_time < 0 || stats.on_time_scan < 0 || energyUsed < 0) {
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
                    sb.append(" scan_time=" + stats.on_time_scan);
                    sb.append(" energy=" + energyUsed);
                    Log.d(TAG, " reportActivityInfo: " + sb.toString());
                }

                // Convert the LinkLayerStats into EnergyActivity
                energyInfo = new WifiActivityEnergyInfo(mClock.getElapsedSinceBootMillis(),
                        WifiActivityEnergyInfo.STACK_STATE_STATE_IDLE, stats.tx_time,
                        txTimePerLevel, stats.rx_time, stats.on_time_scan, rxIdleTime, energyUsed);
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
        if (mVerboseLoggingEnabled) {
            mLog.info("getConfiguredNetworks uid=%").c(Binder.getCallingUid()).flush();
        }
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
        if (mVerboseLoggingEnabled) {
            mLog.info("getPrivilegedConfiguredNetworks uid=%").c(Binder.getCallingUid()).flush();
        }
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
        if (mVerboseLoggingEnabled) {
            mLog.info("getMatchingWifiConfig uid=%").c(Binder.getCallingUid()).flush();
        }
        if (!mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_PASSPOINT)) {
            throw new UnsupportedOperationException("Passpoint not enabled");
        }
        return mWifiStateMachine.syncGetMatchingWifiConfig(scanResult, mWifiStateMachineChannel);
    }

    /**
     * Return the list of all matching Wifi configurations for this ScanResult.
     *
     * An empty list will be returned when no configurations are installed or if no configurations
     * match the ScanResult.
     *
     * @param scanResult scanResult that represents the BSSID
     * @return A list of {@link WifiConfiguration}
     */
    @Override
    public List<WifiConfiguration> getAllMatchingWifiConfigs(ScanResult scanResult) {
        enforceAccessPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("getMatchingPasspointConfigurations uid=%").c(Binder.getCallingUid()).flush();
        }
        if (!mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_PASSPOINT)) {
            throw new UnsupportedOperationException("Passpoint not enabled");
        }
        return mWifiStateMachine.getAllMatchingWifiConfigs(scanResult, mWifiStateMachineChannel);
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
        if (mVerboseLoggingEnabled) {
            mLog.info("getMatchingOsuProviders uid=%").c(Binder.getCallingUid()).flush();
        }
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
    public int addOrUpdateNetwork(WifiConfiguration config, String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return -1;
        }
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
            if (!addOrUpdatePasspointConfiguration(passpointConfig, packageName)) {
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
    public boolean removeNetwork(int netId, String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
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
    public boolean enableNetwork(int netId, boolean disableOthers, String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
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
    public boolean disableNetwork(int netId, String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
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
        int uid = Binder.getCallingUid();
        if (mVerboseLoggingEnabled) {
            mLog.info("getConnectionInfo uid=%").c(uid).flush();
        }
        long ident = Binder.clearCallingIdentity();
        try {
            WifiInfo result = mWifiStateMachine.syncRequestConnectionInfo();
            boolean hideDefaultMacAddress = true;
            boolean hideBssidAndSsid = true;

            try {
                if (mWifiInjector.getWifiPermissionsWrapper().getLocalMacAddressPermission(uid)
                        == PackageManager.PERMISSION_GRANTED) {
                    hideDefaultMacAddress = false;
                }
                mWifiPermissionsUtil.enforceCanAccessScanResults(callingPackage, uid);
                hideBssidAndSsid = false;
            } catch (RemoteException e) {
                Log.e(TAG, "Error checking receiver permission", e);
            } catch (SecurityException e) {
            }
            if (hideDefaultMacAddress) {
                result.setMacAddress(WifiInfo.DEFAULT_MAC_ADDRESS);
            }
            if (hideBssidAndSsid) {
                result.setBSSID(WifiInfo.DEFAULT_MAC_ADDRESS);
                result.setSSID(WifiSsid.createFromHex(null));
            }
            if (mVerboseLoggingEnabled && (hideBssidAndSsid || hideDefaultMacAddress)) {
                mLog.v("getConnectionInfo: hideBssidAndSSid=" + hideBssidAndSsid
                        + ", hideDefaultMacAddress=" + hideDefaultMacAddress);
            }
            return result;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
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
        if (mVerboseLoggingEnabled) {
            mLog.info("getScanResults uid=%").c(uid).flush();
        }
        try {
            mWifiPermissionsUtil.enforceCanAccessScanResults(callingPackage, uid);
            final List<ScanResult> scanResults = new ArrayList<>();
            boolean success = mWifiInjector.getWifiStateMachineHandler().runWithScissors(() -> {
                scanResults.addAll(mScanRequestProxy.getScanResults());
            }, RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
            if (!success) {
                Log.e(TAG, "Failed to post runnable to fetch scan results");
            }
            return scanResults;
        } catch (SecurityException e) {
            return new ArrayList<ScanResult>();
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
    public boolean addOrUpdatePasspointConfiguration(
            PasspointConfiguration config, String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
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
    public boolean removePasspointConfiguration(String fqdn, String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
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
        if (mVerboseLoggingEnabled) {
            mLog.info("getPasspointConfigurations uid=%").c(Binder.getCallingUid()).flush();
        }
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
     * Set the country code
     * @param countryCode ISO 3166 country code.
     *
     */
    @Override
    public void setCountryCode(String countryCode) {
        Slog.i(TAG, "WifiService trying to set country code to " + countryCode);
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
        if (mVerboseLoggingEnabled) {
            mLog.info("getCountryCode uid=%").c(Binder.getCallingUid()).flush();
        }
        String country = mCountryCode.getCountryCode();
        return country;
    }

    @Override
    public boolean isDualBandSupported() {
        //TODO (b/80552904): Should move towards adding a driver API that checks at runtime
        if (mVerboseLoggingEnabled) {
            mLog.info("isDualBandSupported uid=%").c(Binder.getCallingUid()).flush();
        }

        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_wifi_dual_band_support);
    }

    /**
     * Method allowing callers with NETWORK_SETTINGS permission to check if this is a dual mode
     * capable device (STA+AP).
     *
     * @return true if a dual mode capable device
     */
    @Override
    public boolean needs5GHzToAnyApBandConversion() {
        enforceNetworkSettingsPermission();

        if (mVerboseLoggingEnabled) {
            mLog.info("needs5GHzToAnyApBandConversion uid=%").c(Binder.getCallingUid()).flush();
        }
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_wifi_convert_apband_5ghz_to_any);
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
        if (mVerboseLoggingEnabled) {
            mLog.info("getDhcpInfo uid=%").c(Binder.getCallingUid()).flush();
        }
        DhcpResults dhcpResults = mWifiStateMachine.syncGetDhcpResults();

        DhcpInfo info = new DhcpInfo();

        if (dhcpResults.ipAddress != null &&
                dhcpResults.ipAddress.getAddress() instanceof Inet4Address) {
            info.ipAddress = NetworkUtils.inetAddressToInt(
                    (Inet4Address) dhcpResults.ipAddress.getAddress());
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
    public Messenger getWifiServiceMessenger(String packageName) throws RemoteException {
        enforceAccessPermission();
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            // We don't have a good way of creating a fake Messenger, and returning null would
            // immediately break callers.
            throw new SecurityException("Could not create wifi service messenger");
        }
        mLog.info("getWifiServiceMessenger uid=%").c(Binder.getCallingUid()).flush();
        return new Messenger(mClientHandler);
    }

    /**
     * Disable an ephemeral network, i.e. network that is created thru a WiFi Scorer
     */
    @Override
    public void disableEphemeralNetwork(String SSID, String packageName) {
        enforceAccessPermission();
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return;
        }
        mLog.info("disableEphemeralNetwork uid=%").c(Binder.getCallingUid()).flush();
        mWifiStateMachine.disableEphemeralNetwork(SSID);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_USER_PRESENT)) {
                mWifiController.sendMessage(CMD_USER_PRESENT);
            } else if (action.equals(Intent.ACTION_USER_REMOVED)) {
                int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                mWifiStateMachine.removeUserConfigs(userHandle);
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
        if (UserHandle.getAppId(callingUid) == Process.SYSTEM_UID
                || checkWifiPermissionWhenPermissionReviewRequired()) {
            return false;
        }
        try {
            // Validate the package only if we are going to use it
            ApplicationInfo applicationInfo = mContext.getPackageManager()
                    .getApplicationInfoAsUser(packageName,
                            PackageManager.MATCH_DEBUG_TRIAGED_MISSING,
                            UserHandle.getUserId(callingUid));
            if (applicationInfo.uid != callingUid) {
                throw new SecurityException("Package " + packageName
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

    private void registerForBroadcasts() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_PRESENT);
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
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

        intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
        intentFilter.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action.equals(Intent.ACTION_PACKAGE_FULLY_REMOVED)) {
                    int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                    Uri uri = intent.getData();
                    if (uid == -1 || uri == null) {
                        return;
                    }
                    String pkgName = uri.getSchemeSpecificPart();
                    mWifiStateMachine.removeAppConfigs(pkgName, uid);
                    // Call the method in WSM thread.
                    mWifiStateMachineHandler.post(() -> {
                        mScanRequestProxy.clearScanRequestTimestampsForApp(pkgName, uid);
                    });
                }
            }
        }, intentFilter);
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
        } else if (args != null && args.length > 0 && IpClient.DUMP_ARG.equals(args[0])) {
            // IpClient dump was requested. Pass it along and take no further action.
            String[] ipClientArgs = new String[args.length - 1];
            System.arraycopy(args, 1, ipClientArgs, 0, ipClientArgs.length);
            mWifiStateMachine.dumpIpClient(fd, pw, ipClientArgs);
        } else if (args != null && args.length > 0 && WifiScoreReport.DUMP_ARG.equals(args[0])) {
            WifiScoreReport wifiScoreReport = mWifiStateMachine.getWifiScoreReport();
            if (wifiScoreReport != null) wifiScoreReport.dump(fd, pw, args);
        } else {
            pw.println("Wi-Fi is " + mWifiStateMachine.syncGetWifiStateByName());
            pw.println("Verbose logging is " + (mVerboseLoggingEnabled ? "on" : "off"));
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
            mWifiStateMachinePrime.dump(fd, pw, args);
            pw.println();
            mWifiStateMachine.dump(fd, pw, args);
            pw.println();
            mWifiStateMachine.updateWifiMetrics();
            mWifiMetrics.dump(fd, pw, args);
            pw.println();
            mWifiBackupRestore.dump(fd, pw, args);
            pw.println();
            pw.println("ScoringParams: settings put global " + Settings.Global.WIFI_SCORE_PARAMS
                       + " " + mWifiInjector.getScoringParams());
            pw.println();
            WifiScoreReport wifiScoreReport = mWifiStateMachine.getWifiScoreReport();
            if (wifiScoreReport != null) {
                pw.println("WifiScoreReport:");
                wifiScoreReport.dump(fd, pw, args);
            }
            pw.println();
        }
    }

    /**
     * NOTE: WifiLocks do not serve a useful purpose in their current impl and will be removed
     * (including the methods below).
     *
     * TODO: b/71548157
     */
    @Override
    public boolean acquireWifiLock(IBinder binder, int lockMode, String tag, WorkSource ws) {
        mLog.info("acquireWifiLock uid=% lockMode=%")
                .c(Binder.getCallingUid())
                .c(lockMode).flush();
        if (mWifiLockManager.acquireWifiLock(lockMode, tag, binder, ws)) {
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
        if (mVerboseLoggingEnabled) {
            mLog.info("isMulticastEnabled uid=%").c(Binder.getCallingUid()).flush();
        }
        return mWifiMulticastLockManager.isMulticastEnabled();
    }

    @Override
    public void enableVerboseLogging(int verbose) {
        enforceAccessPermission();
        enforceNetworkSettingsPermission();
        mLog.info("enableVerboseLogging uid=% verbose=%")
                .c(Binder.getCallingUid())
                .c(verbose).flush();
        mFacade.setIntegerSetting(
                mContext, Settings.Global.WIFI_VERBOSE_LOGGING_ENABLED, verbose);
        enableVerboseLoggingInternal(verbose);
    }

    void enableVerboseLoggingInternal(int verbose) {
        mVerboseLoggingEnabled = verbose > 0;
        mWifiStateMachine.enableVerboseLogging(verbose);
        mWifiLockManager.enableVerboseLogging(verbose);
        mWifiMulticastLockManager.enableVerboseLogging(verbose);
        mWifiInjector.enableVerboseLogging(verbose);
    }

    @Override
    public int getVerboseLoggingLevel() {
        enforceAccessPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("getVerboseLoggingLevel uid=%").c(Binder.getCallingUid()).flush();
        }
        return mFacade.getIntegerSetting(
                mContext, Settings.Global.WIFI_VERBOSE_LOGGING_ENABLED, 0);
    }

    @Override
    public void factoryReset(String packageName) {
        enforceConnectivityInternalPermission();
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return;
        }
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
            // Delete all Wifi SSIDs
            if (mWifiStateMachineChannel != null) {
                List<WifiConfiguration> networks = mWifiStateMachine.syncGetConfiguredNetworks(
                        Binder.getCallingUid(), mWifiStateMachineChannel);
                if (networks != null) {
                    for (WifiConfiguration config : networks) {
                        removeNetwork(config.networkId, packageName);
                    }
                }
            }
        }
    }

    /* private methods */
    static boolean logAndReturnFalse(String s) {
        Log.d(TAG, s);
        return false;
    }

    @Override
    public Network getCurrentNetwork() {
        enforceAccessPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("getCurrentNetwork uid=%").c(Binder.getCallingUid()).flush();
        }
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

    /**
     * Starts subscription provisioning with a provider
     *
     * @param provider {@link OsuProvider} the provider to provision with
     * @param callback {@link IProvisoningCallback} the callback object to inform status
     */
    @Override
    public void startSubscriptionProvisioning(OsuProvider provider,
            IProvisioningCallback callback) {
        if (provider == null) {
            throw new IllegalArgumentException("Provider must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }
        enforceNetworkSettingsPermission();
        if (!mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_PASSPOINT)) {
            throw new UnsupportedOperationException("Passpoint not enabled");
        }
        final int uid = Binder.getCallingUid();
        mLog.trace("startSubscriptionProvisioning uid=%").c(uid).flush();
        if (mWifiStateMachine.syncStartSubscriptionProvisioning(uid, provider,
                callback, mWifiStateMachineChannel)) {
            mLog.trace("Subscription provisioning started with %")
                    .c(provider.toString()).flush();
        }
    }
}
