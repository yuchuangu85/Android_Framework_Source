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
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
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
import static android.net.wifi.WifiManager.WIFI_FEATURE_INFRA_5G;

import static com.android.server.wifi.LocalOnlyHotspotRequestInfo.HOTSPOT_NO_ERROR;
import static com.android.server.wifi.WifiController.CMD_AIRPLANE_TOGGLED;
import static com.android.server.wifi.WifiController.CMD_EMERGENCY_CALL_STATE_CHANGED;
import static com.android.server.wifi.WifiController.CMD_EMERGENCY_MODE_CHANGED;
import static com.android.server.wifi.WifiController.CMD_SCAN_ALWAYS_MODE_CHANGED;
import static com.android.server.wifi.WifiController.CMD_SET_AP;
import static com.android.server.wifi.WifiController.CMD_WIFI_TOGGLED;

import android.Manifest;
import android.annotation.CheckResult;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.AppOpsManager;
import android.app.admin.DeviceAdminInfo;
import android.app.admin.DevicePolicyManagerInternal;
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
import android.net.ip.IpClientUtil;
import android.net.wifi.IDppCallback;
import android.net.wifi.INetworkRequestMatchCallback;
import android.net.wifi.IOnWifiUsabilityStatsListener;
import android.net.wifi.ISoftApCallback;
import android.net.wifi.ITrafficStateCallback;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiActivityEnergyInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.DeviceMobilityState;
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback;
import android.net.wifi.WifiNetworkSuggestion;
import android.net.wifi.WifiSsid;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.os.AsyncTask;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
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
import android.telephony.TelephonyManager;
import android.text.TextUtils;
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
import com.android.server.wifi.util.ExternalCallbackTracker;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WifiService handles remote WiFi operation requests by implementing
 * the IWifiManager interface.
 *
 * @hide
 */
public class WifiServiceImpl extends BaseWifiService {
    private static final String TAG = "WifiService";
    private static final boolean VDBG = false;

    // Default scan background throttling interval if not overriden in settings
    private static final long DEFAULT_SCAN_BACKGROUND_THROTTLE_INTERVAL_MS = 30 * 60 * 1000;

    // Apps with importance higher than this value is considered as background app.
    private static final int BACKGROUND_IMPORTANCE_CUTOFF =
            RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;

    // Max wait time for posting blocking runnables
    private static final int RUN_WITH_SCISSORS_TIMEOUT_MILLIS = 4000;

    final ClientModeImpl mClientModeImpl;
    final ActiveModeWarden mActiveModeWarden;
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
    private WifiTrafficPoller mWifiTrafficPoller;
    /* Tracks the persisted states for wi-fi & airplane mode */
    final WifiSettingsStore mSettingsStore;
    /* Logs connection events and some general router and scan stats */
    private final WifiMetrics mWifiMetrics;

    private final WifiInjector mWifiInjector;
    /* Backup/Restore Module */
    private final WifiBackupRestore mWifiBackupRestore;
    private final WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;

    private WifiLog mLog;
    /**
     * Verbose logging flag. Toggled by developer options.
     */
    private boolean mVerboseLoggingEnabled = false;

    /**
     * Asynchronous channel to ClientModeImpl
     */
    @VisibleForTesting
    AsyncChannel mClientModeImplChannel;

    private final FrameworkFacade mFrameworkFacade;

    private WifiPermissionsUtil mWifiPermissionsUtil;

    @GuardedBy("mLocalOnlyHotspotRequests")
    private final HashMap<Integer, LocalOnlyHotspotRequestInfo> mLocalOnlyHotspotRequests;
    @GuardedBy("mLocalOnlyHotspotRequests")
    private WifiConfiguration mLocalOnlyHotspotConfig = null;
    @GuardedBy("mLocalOnlyHotspotRequests")
    private final ConcurrentHashMap<String, Integer> mIfaceIpModes;

    private final ExternalCallbackTracker<ISoftApCallback> mRegisteredSoftApCallbacks;

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
    private class AsyncChannelExternalClientHandler extends WifiHandler {

        AsyncChannelExternalClientHandler(String tag, Looper looper) {
            super(tag, looper);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                    AsyncChannel ac = mFrameworkFacade.makeWifiAsyncChannel(TAG);
                    ac.connect(mContext, this, msg.replyTo);
                    break;
                }
                case WifiManager.CONNECT_NETWORK: {
                    if (checkPrivilegedPermissionsAndReplyIfNotAuthorized(
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
                            mClientModeImpl.sendMessage(Message.obtain(msg));
                        } else if (config == null
                                && networkId != WifiConfiguration.INVALID_NETWORK_ID) {
                            mClientModeImpl.sendMessage(Message.obtain(msg));
                        } else {
                            Slog.e(TAG, "AsyncChannelExternalClientHandler.handleMessage "
                                    + "ignoring invalid msg=" + msg);
                            replyFailed(msg, WifiManager.CONNECT_NETWORK_FAILED,
                                    WifiManager.INVALID_ARGS);
                        }
                    }
                    break;
                }
                case WifiManager.SAVE_NETWORK: {
                    if (checkPrivilegedPermissionsAndReplyIfNotAuthorized(
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
                            mClientModeImpl.sendMessage(Message.obtain(msg));
                        } else {
                            Slog.e(TAG, "AsyncChannelExternalClientHandler.handleMessage "
                                    + "ignoring invalid msg=" + msg);
                            replyFailed(msg, WifiManager.SAVE_NETWORK_FAILED,
                                    WifiManager.INVALID_ARGS);
                        }
                    }
                    break;
                }
                case WifiManager.FORGET_NETWORK:
                    if (checkPrivilegedPermissionsAndReplyIfNotAuthorized(
                            msg, WifiManager.FORGET_NETWORK_FAILED)) {
                        mClientModeImpl.sendMessage(Message.obtain(msg));
                    }
                    break;
                case WifiManager.DISABLE_NETWORK:
                    if (checkPrivilegedPermissionsAndReplyIfNotAuthorized(
                            msg, WifiManager.DISABLE_NETWORK_FAILED)) {
                        mClientModeImpl.sendMessage(Message.obtain(msg));
                    }
                    break;
                case WifiManager.RSSI_PKTCNT_FETCH: {
                    if (checkChangePermissionAndReplyIfNotAuthorized(
                            msg, WifiManager.RSSI_PKTCNT_FETCH_FAILED)) {
                        mClientModeImpl.sendMessage(Message.obtain(msg));
                    }
                    break;
                }
                default: {
                    Slog.d(TAG, "AsyncChannelExternalClientHandler.handleMessage "
                            + "ignoring msg=" + msg);
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
                Slog.e(TAG, "AsyncChannelExternalClientHandler.handleMessage "
                        + "ignoring unauthorized msg=" + msg);
                replyFailed(msg, replyWhat, WifiManager.NOT_AUTHORIZED);
                return false;
            }
            return true;
        }

        /**
         * Helper method to check if the sender of the message holds one of
         * {@link Manifest.permission#NETWORK_SETTINGS},
         * {@link Manifest.permission#NETWORK_SETUP_WIZARD} or
         * {@link Manifest.permission#NETWORK_STACK} permission, and reply with a failure if it
         * doesn't
         *
         * @param msg Incoming message.
         * @param replyWhat Param to be filled in the {@link Message#what} field of the failure
         *                  reply.
         * @return true if the sender holds the permission, false otherwise.
         */
        private boolean checkPrivilegedPermissionsAndReplyIfNotAuthorized(
                Message msg, int replyWhat) {
            if (!isPrivileged(-1, msg.sendingUid)) {
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
    private AsyncChannelExternalClientHandler mAsyncChannelExternalClientHandler;

    /**
     * Handles interaction with ClientModeImpl
     */
    private class ClientModeImplHandler extends WifiHandler {
        private AsyncChannel mCmiChannel;

        ClientModeImplHandler(String tag, Looper looper, AsyncChannel asyncChannel) {
            super(tag, looper);
            mCmiChannel = asyncChannel;
            mCmiChannel.connect(mContext, this, mClientModeImpl.getHandler());
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED: {
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        mClientModeImplChannel = mCmiChannel;
                    } else {
                        Slog.e(TAG, "ClientModeImpl connection failure, error=" + msg.arg1);
                        mClientModeImplChannel = null;
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    Slog.e(TAG, "ClientModeImpl channel lost, msg.arg1 =" + msg.arg1);
                    mClientModeImplChannel = null;
                    //Re-establish connection to state machine
                    mCmiChannel.connect(mContext, this, mClientModeImpl.getHandler());
                    break;
                }
                default: {
                    Slog.d(TAG, "ClientModeImplHandler.handleMessage ignoring msg=" + msg);
                    break;
                }
            }
        }
    }

    ClientModeImplHandler mClientModeImplHandler;
    private WifiController mWifiController;
    private final WifiLockManager mWifiLockManager;
    private final WifiMulticastLockManager mWifiMulticastLockManager;
    private final DppManager mDppManager;

    private WifiApConfigStore mWifiApConfigStore;

    public WifiServiceImpl(Context context, WifiInjector wifiInjector, AsyncChannel asyncChannel) {
        mContext = context;
        mWifiInjector = wifiInjector;
        mClock = wifiInjector.getClock();

        mFacade = mWifiInjector.getFrameworkFacade();
        mWifiMetrics = mWifiInjector.getWifiMetrics();
        mWifiTrafficPoller = mWifiInjector.getWifiTrafficPoller();
        mUserManager = mWifiInjector.getUserManager();
        mCountryCode = mWifiInjector.getWifiCountryCode();
        mClientModeImpl = mWifiInjector.getClientModeImpl();
        mActiveModeWarden = mWifiInjector.getActiveModeWarden();
        mClientModeImpl.enableRssiPolling(true);
        mScanRequestProxy = mWifiInjector.getScanRequestProxy();
        mSettingsStore = mWifiInjector.getWifiSettingsStore();
        mPowerManager = mContext.getSystemService(PowerManager.class);
        mAppOps = (AppOpsManager) mContext.getSystemService(Context.APP_OPS_SERVICE);
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mWifiLockManager = mWifiInjector.getWifiLockManager();
        mWifiMulticastLockManager = mWifiInjector.getWifiMulticastLockManager();
        HandlerThread wifiServiceHandlerThread = mWifiInjector.getWifiServiceHandlerThread();
        mAsyncChannelExternalClientHandler =
                new AsyncChannelExternalClientHandler(TAG, wifiServiceHandlerThread.getLooper());
        mClientModeImplHandler = new ClientModeImplHandler(TAG,
                wifiServiceHandlerThread.getLooper(), asyncChannel);
        mWifiController = mWifiInjector.getWifiController();
        mWifiBackupRestore = mWifiInjector.getWifiBackupRestore();
        mWifiApConfigStore = mWifiInjector.getWifiApConfigStore();
        mWifiPermissionsUtil = mWifiInjector.getWifiPermissionsUtil();
        mLog = mWifiInjector.makeLog(TAG);
        mFrameworkFacade = wifiInjector.getFrameworkFacade();
        mIfaceIpModes = new ConcurrentHashMap<>();
        mLocalOnlyHotspotRequests = new HashMap<>();
        enableVerboseLoggingInternal(getVerboseLoggingLevel());
        mRegisteredSoftApCallbacks =
                new ExternalCallbackTracker<ISoftApCallback>(mClientModeImplHandler);

        mWifiInjector.getActiveModeWarden().registerSoftApCallback(new SoftApCallbackImpl());
        mPowerProfile = mWifiInjector.getPowerProfile();
        mWifiNetworkSuggestionsManager = mWifiInjector.getWifiNetworkSuggestionsManager();
        mDppManager = mWifiInjector.getDppManager();
    }

    /**
     * Provide a way for unit tests to set valid log object in the WifiHandler
     * @param log WifiLog object to assign to the clientHandler
     */
    @VisibleForTesting
    public void setWifiHandlerLogForTest(WifiLog log) {
        mAsyncChannelExternalClientHandler.setWifiLog(log);
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
                            mClientModeImpl.resetSimAuthNetworks(false);
                        } else if (IccCardConstants.INTENT_VALUE_ICC_LOADED.equals(state)) {
                            Log.d(TAG, "resetting networks because SIM was loaded");
                            mClientModeImpl.resetSimAuthNetworks(true);
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

        if (!mClientModeImpl.syncInitialize(mClientModeImplChannel)) {
            Log.wtf(TAG, "Failed to initialize ClientModeImpl");
        }
        mWifiController.start();

        // If we are already disabled (could be due to airplane mode), avoid changing persist
        // state here
        if (wifiEnabled) {
            setWifiEnabled(mContext.getPackageName(), wifiEnabled);
        }
    }

    public void handleBootCompleted() {
        Log.d(TAG, "Handle boot completed");
        mClientModeImpl.handleBootCompleted();
    }

    public void handleUserSwitch(int userId) {
        Log.d(TAG, "Handle user switch " + userId);
        mClientModeImpl.handleUserSwitch(userId);
    }

    public void handleUserUnlock(int userId) {
        Log.d(TAG, "Handle user unlock " + userId);
        mClientModeImpl.handleUserUnlock(userId);
    }

    public void handleUserStop(int userId) {
        Log.d(TAG, "Handle user stop " + userId);
        mClientModeImpl.handleUserStop(userId);
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
            boolean runWithScissorsSuccess = mWifiInjector.getClientModeImplHandler()
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
            Slog.e(TAG, "Permission violation - startScan not allowed for"
                    + " uid=" + callingUid + ", packageName=" + packageName + ", reason=" + e);
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
                == PERMISSION_GRANTED;
    }

    private boolean checkNetworkSetupWizardPermission(int pid, int uid) {
        return mContext.checkPermission(android.Manifest.permission.NETWORK_SETUP_WIZARD, pid, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkNetworkStackPermission(int pid, int uid) {
        return mContext.checkPermission(android.Manifest.permission.NETWORK_STACK, pid, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkNetworkManagedProvisioningPermission(int pid, int uid) {
        return mContext.checkPermission(android.Manifest.permission.NETWORK_MANAGED_PROVISIONING,
                pid, uid) == PackageManager.PERMISSION_GRANTED;
    }

    // Helper method to check if the entity initiating the binder call has any of the signature only
    // permissions.
    private boolean isPrivileged(int pid, int uid) {
        return checkNetworkSettingsPermission(pid, uid)
                || checkNetworkSetupWizardPermission(pid, uid)
                || checkNetworkStackPermission(pid, uid)
                || checkNetworkManagedProvisioningPermission(pid, uid);
    }

    // Helper method to check if the entity initiating the binder call has setup wizard or settings
    // permissions.
    private boolean isSettingsOrSuw(int pid, int uid) {
        return checkNetworkSettingsPermission(pid, uid)
                || checkNetworkSetupWizardPermission(pid, uid);
    }

    // Helper method to check if the entity initiating the binder call is a system app.
    private boolean isSystem(String packageName) {
        long ident = Binder.clearCallingIdentity();
        try {
            ApplicationInfo info = mContext.getPackageManager().getApplicationInfo(packageName, 0);
            return info.isSystemApp() || info.isUpdatedSystemApp();
        } catch (PackageManager.NameNotFoundException e) {
            // In case of exception, assume unknown app (more strict checking)
            // Note: This case will never happen since checkPackage is
            // called to verify validity before checking App's version.
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return false;
    }

    // Helper method to check if the entity initiating the binder call is a DO/PO app.
    private boolean isDeviceOrProfileOwner(int uid) {
        final DevicePolicyManagerInternal dpmi =
                mWifiInjector.getWifiPermissionsWrapper().getDevicePolicyManagerInternal();
        if (dpmi == null) return false;
        return dpmi.isActiveAdminWithPolicy(uid, DeviceAdminInfo.USES_POLICY_DEVICE_OWNER)
                || dpmi.isActiveAdminWithPolicy(uid, DeviceAdminInfo.USES_POLICY_PROFILE_OWNER);
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
        mAppOps.checkPackage(Binder.getCallingUid(), callingPackage);
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
     * Helper method to check if the app is allowed to access public API's deprecated in
     * {@link Build.VERSION_CODES.Q}.
     * Note: Invoke mAppOps.checkPackage(uid, packageName) before to ensure correct package name.
     */
    private boolean isTargetSdkLessThanQOrPrivileged(String packageName, int pid, int uid) {
        return mWifiPermissionsUtil.isTargetSdkLessThan(packageName, Build.VERSION_CODES.Q)
                || isPrivileged(pid, uid)
                // DO/PO apps should be able to add/modify saved networks.
                || isDeviceOrProfileOwner(uid)
                // TODO: Remove this system app bypass once Q is released.
                || isSystem(packageName)
                || mWifiPermissionsUtil.checkSystemAlertWindowPermission(uid, packageName);
    }

    /**
     * see {@link android.net.wifi.WifiManager#setWifiEnabled(boolean)}
     * @param enable {@code true} to enable, {@code false} to disable.
     * @return {@code true} if the enable/disable operation was
     *         started or is already in the queue.
     */
    @Override
    public synchronized boolean setWifiEnabled(String packageName, boolean enable) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
        boolean isPrivileged = isPrivileged(Binder.getCallingPid(), Binder.getCallingUid());
        if (!isPrivileged
                && !mWifiPermissionsUtil.isTargetSdkLessThan(packageName, Build.VERSION_CODES.Q)) {
            mLog.info("setWifiEnabled not allowed for uid=%")
                    .c(Binder.getCallingUid()).flush();
            return false;
        }
        // If Airplane mode is enabled, only privileged apps are allowed to toggle Wifi
        if (mSettingsStore.isAirplaneModeOn() && !isPrivileged) {
            mLog.err("setWifiEnabled in Airplane mode: only Settings can toggle wifi").flush();
            return false;
        }

        // If SoftAp is enabled, only privileged apps are allowed to toggle wifi
        boolean apEnabled = mWifiApState == WifiManager.WIFI_AP_STATE_ENABLED;
        if (apEnabled && !isPrivileged) {
            mLog.err("setWifiEnabled SoftAp enabled: only Settings can toggle wifi").flush();
            return false;
        }

        mLog.info("setWifiEnabled package=% uid=% enable=%").c(packageName)
                .c(Binder.getCallingUid()).c(enable).flush();
        long ident = Binder.clearCallingIdentity();
        try {
            if (!mSettingsStore.handleWifiToggled(enable)) {
                // Nothing to do if wifi cannot be toggled
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        mWifiMetrics.incrementNumWifiToggles(isPrivileged, enable);
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
        return mClientModeImpl.syncGetWifiState();
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
        mWifiInjector.getClientModeImplHandler().runWithScissors(() -> {
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
        mWifiInjector.getClientModeImplHandler().post(() -> {
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
                    if (!isConcurrentLohsAndTetheringSupported()) {
                        /* We have tethered an interface. We don't really act on this now other than
                         * if we have LOHS requests, and this is an issue. Return incompatible mode
                         * for onFailed for the registered requestors since this can result from a
                         * race between a tether request and a hotspot request (tethering wins). */
                        sendHotspotFailedMessageToAllLOHSRequestInfoEntriesLocked(
                                LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE);
                    }
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
            // If a tethering request comes in while we have an existing tethering session, return
            // error.
            if (mIfaceIpModes.contains(WifiManager.IFACE_IP_MODE_TETHERED)) {
                mLog.err("Tethering is already active.").flush();
                return false;
            }
            // If a tethering request comes in while we have LOHS running (or requested), call stop
            // for softap mode and restart softap with the tethering config.
            if (!isConcurrentLohsAndTetheringSupported() && !mLocalOnlyHotspotRequests.isEmpty()) {
                stopSoftApInternal(WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
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
                // This shouldn't affect devices that support concurrent LOHS and tethering
                mLog.trace("Call to stop Tethering while LOHS is active,"
                        + " Registered LOHS callers will be updated when softap stopped.").flush();
            }

            return stopSoftApInternal(WifiManager.IFACE_IP_MODE_TETHERED);
        }
    }

    /**
     * Internal method to stop softap mode.  Callers of this method should have already checked
     * proper permissions beyond the NetworkStack permission.
     */
    private boolean stopSoftApInternal(int mode) {
        mLog.trace("stopSoftApInternal uid=%").c(Binder.getCallingUid()).flush();

        mWifiController.sendMessage(CMD_SET_AP, 0, mode);
        return true;
    }

    /**
     * Callback to use with ClientModeImpl to receive events from ClientModeImpl
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

            Iterator<ISoftApCallback> iterator =
                    mRegisteredSoftApCallbacks.getCallbacks().iterator();
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

            Iterator<ISoftApCallback> iterator =
                    mRegisteredSoftApCallbacks.getCallbacks().iterator();
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

        // post operation to handler thread
        mWifiInjector.getClientModeImplHandler().post(() -> {
            if (!mRegisteredSoftApCallbacks.add(binder, callback, callbackIdentifier)) {
                Log.e(TAG, "registerSoftApCallback: Failed to add callback");
                return;
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
        mWifiInjector.getClientModeImplHandler().post(() -> {
            mRegisteredSoftApCallbacks.remove(callbackIdentifier);
        });
    }

    /**
     * Private method to handle SoftAp state changes
     *
     * <p> MUST be called from the ClientModeImpl thread.
     */
    private void handleWifiApStateChange(
            int currentState, int previousState, int errorCode, String ifaceName, int mode) {
        // The AP state update from ClientModeImpl for softap
        Slog.d(TAG, "handleWifiApStateChange: currentState=" + currentState
                + " previousState=" + previousState + " errorCode= " + errorCode
                + " ifaceName=" + ifaceName + " mode=" + mode);

        // update the tracking ap state variable
        mWifiApState = currentState;

        // check if we have a failure - since it is possible (worst case scenario where
        // WifiController and ClientModeImpl are out of sync wrt modes) to get two FAILED
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
                if (mIfaceIpModes.getOrDefault(ifaceName, WifiManager.IFACE_IP_MODE_UNSPECIFIED)
                        == WifiManager.IFACE_IP_MODE_LOCAL_ONLY) {
                    // holding the required lock: send message to requestors and clear the list
                    sendHotspotStoppedMessageToAllLOHSRequestInfoEntriesLocked();
                } else if (!isConcurrentLohsAndTetheringSupported()) {
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
        long ident = Binder.clearCallingIdentity();
        try {
            // also need to verify that Locations services are enabled.
            if (!mWifiPermissionsUtil.isLocationModeEnabled()) {
                throw new SecurityException("Location mode is not enabled.");
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        // verify that tethering is not disabled
        if (mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING)) {
            return LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED;
        }

        // the app should be in the foreground
        if (!mFrameworkFacade.isAppForeground(uid)) {
            return LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE;
        }

        mLog.info("startLocalOnlyHotspot uid=% pid=%").c(uid).c(pid).flush();

        synchronized (mLocalOnlyHotspotRequests) {
            // check if we are currently tethering
            // TODO(b/123227116): handle all interface combinations just by changing the HAL.
            if (!isConcurrentLohsAndTetheringSupported()
                    && mIfaceIpModes.contains(WifiManager.IFACE_IP_MODE_TETHERED)) {
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
                boolean is5Ghz = hasAutomotiveFeature(mContext)
                        && mContext.getResources().getBoolean(
                        com.android.internal.R.bool.config_wifi_local_only_hotspot_5ghz)
                        && is5GhzSupported();

                mLocalOnlyHotspotConfig = WifiApConfigStore.generateLocalOnlyHotspotConfig(mContext,
                        is5Ghz ? WifiConfiguration.AP_BAND_5GHZ : WifiConfiguration.AP_BAND_2GHZ);

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
                    stopSoftApInternal(WifiManager.IFACE_IP_MODE_LOCAL_ONLY);
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

        // hand off work to the ClientModeImpl handler thread to sync work between calls
        // and SoftApManager starting up softap
        final Mutable<WifiConfiguration> config = new Mutable();
        boolean success = mWifiInjector.getClientModeImplHandler().runWithScissors(() -> {
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
            mClientModeImplHandler.post(() -> {
                mWifiApConfigStore.setApConfiguration(wifiConfig);
            });
            return true;
        } else {
            Slog.e(TAG, "Invalid WifiConfiguration");
            return false;
        }
    }

    /**
     * Method used to inform user of Ap Configuration conversion due to hardware.
     */
    @Override
    public void notifyUserOfApBandConversion(String packageName) {
        enforceNetworkSettingsPermission();

        if (mVerboseLoggingEnabled) {
            mLog.info("notifyUserOfApBandConversion uid=% packageName=%")
                    .c(Binder.getCallingUid()).c(packageName).flush();
        }

        mWifiApConfigStore.notifyUserOfApBandConversion(packageName);
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
    public boolean disconnect(String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
        if (!isTargetSdkLessThanQOrPrivileged(
                packageName, Binder.getCallingPid(), Binder.getCallingUid())) {
            mLog.info("disconnect not allowed for uid=%")
                    .c(Binder.getCallingUid()).flush();
            return false;
        }
        mLog.info("disconnect uid=%").c(Binder.getCallingUid()).flush();
        mClientModeImpl.disconnectCommand();
        return true;
    }

    /**
     * see {@link android.net.wifi.WifiManager#reconnect()}
     */
    @Override
    public boolean reconnect(String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
        if (!isTargetSdkLessThanQOrPrivileged(
                packageName, Binder.getCallingPid(), Binder.getCallingUid())) {
            mLog.info("reconnect not allowed for uid=%")
                    .c(Binder.getCallingUid()).flush();
            return false;
        }
        mLog.info("reconnect uid=%").c(Binder.getCallingUid()).flush();
        mClientModeImpl.reconnectCommand(new WorkSource(Binder.getCallingUid()));
        return true;
    }

    /**
     * see {@link android.net.wifi.WifiManager#reassociate()}
     */
    @Override
    public boolean reassociate(String packageName) {
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            return false;
        }
        if (!isTargetSdkLessThanQOrPrivileged(
                packageName, Binder.getCallingPid(), Binder.getCallingUid())) {
            mLog.info("reassociate not allowed for uid=%")
                    .c(Binder.getCallingUid()).flush();
            return false;
        }
        mLog.info("reassociate uid=%").c(Binder.getCallingUid()).flush();
        mClientModeImpl.reassociateCommand();
        return true;
    }

    /**
     * see {@link android.net.wifi.WifiManager#getSupportedFeatures}
     */
    @Override
    public long getSupportedFeatures() {
        enforceAccessPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("getSupportedFeatures uid=%").c(Binder.getCallingUid()).flush();
        }
        return getSupportedFeaturesInternal();
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
        if (mClientModeImplChannel != null) {
            stats = mClientModeImpl.syncGetLinkLayerStats(mClientModeImplChannel);
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
            Slog.e(TAG, "mClientModeImplChannel is not initialized");
            return null;
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#getConfiguredNetworks()}
     *
     * @param packageName String name of the calling package
     * @return the list of configured networks
     */
    @Override
    public ParceledListSlice<WifiConfiguration> getConfiguredNetworks(String packageName) {
        enforceAccessPermission();
        int callingUid = Binder.getCallingUid();
        // bypass shell: can get varioud pkg name
        if (callingUid != Process.SHELL_UID && callingUid != Process.ROOT_UID) {
            long ident = Binder.clearCallingIdentity();
            try {
                mWifiPermissionsUtil.enforceCanAccessScanResults(packageName, callingUid);
            } catch (SecurityException e) {
                Slog.e(TAG, "Permission violation - getConfiguredNetworks not allowed for uid="
                        + callingUid + ", packageName=" + packageName + ", reason=" + e);
                return new ParceledListSlice<>(new ArrayList<>());
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
        boolean isTargetSdkLessThanQOrPrivileged = isTargetSdkLessThanQOrPrivileged(
                packageName, Binder.getCallingPid(), callingUid);
        boolean isCarrierApp = mWifiInjector.makeTelephonyManager()
                .checkCarrierPrivilegesForPackageAnyPhone(packageName)
                == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS;
        if (!isTargetSdkLessThanQOrPrivileged && !isCarrierApp) {
            mLog.info("getConfiguredNetworks not allowed for uid=%")
                    .c(callingUid).flush();
            return new ParceledListSlice<>(new ArrayList<>());
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getConfiguredNetworks uid=%").c(callingUid).flush();
        }

        int targetConfigUid = Process.INVALID_UID; // don't expose any MAC addresses
        if (isPrivileged(getCallingPid(), callingUid) || isDeviceOrProfileOwner(callingUid)) {
            targetConfigUid = Process.WIFI_UID; // expose all MAC addresses
        } else if (isCarrierApp) {
            targetConfigUid = callingUid; // expose only those configs created by the Carrier App
        }

        if (mClientModeImplChannel != null) {
            List<WifiConfiguration> configs = mClientModeImpl.syncGetConfiguredNetworks(
                    callingUid, mClientModeImplChannel, targetConfigUid);
            if (configs != null) {
                if (isTargetSdkLessThanQOrPrivileged) {
                    return new ParceledListSlice<WifiConfiguration>(configs);
                } else { // Carrier app: should only get its own configs
                    List<WifiConfiguration> creatorConfigs = new ArrayList<>();
                    for (WifiConfiguration config : configs) {
                        if (config.creatorUid == callingUid) {
                            creatorConfigs.add(config);
                        }
                    }
                    return new ParceledListSlice<WifiConfiguration>(creatorConfigs);
                }
            }
        } else {
            Slog.e(TAG, "mClientModeImplChannel is not initialized");
        }
        return null;
    }

    /**
     * see {@link android.net.wifi.WifiManager#getPrivilegedConfiguredNetworks()}
     *
     * @param packageName String name of the calling package
     * @return the list of configured networks with real preSharedKey
     */
    @Override
    public ParceledListSlice<WifiConfiguration>
            getPrivilegedConfiguredNetworks(String packageName) {
        enforceReadCredentialPermission();
        enforceAccessPermission();
        int callingUid = Binder.getCallingUid();
        long ident = Binder.clearCallingIdentity();
        try {
            mWifiPermissionsUtil.enforceCanAccessScanResults(packageName, callingUid);
        } catch (SecurityException e) {
            Slog.e(TAG, "Permission violation - getPrivilegedConfiguredNetworks not allowed for"
                    + " uid=" + callingUid + ", packageName=" + packageName + ", reason=" + e);
            return null;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getPrivilegedConfiguredNetworks uid=%").c(callingUid).flush();
        }
        if (mClientModeImplChannel != null) {
            List<WifiConfiguration> configs =
                    mClientModeImpl.syncGetPrivilegedConfiguredNetwork(mClientModeImplChannel);
            if (configs != null) {
                return new ParceledListSlice<WifiConfiguration>(configs);
            }
        } else {
            Slog.e(TAG, "mClientModeImplChannel is not initialized");
        }
        return null;
    }

    /**
     * Returns the list of FQDN (Fully Qualified Domain Name) to installed Passpoint configurations.
     *
     * Return the map of all matching configurations with corresponding scanResults (or an empty map
     * if none).
     *
     * @param scanResults The list of scan results
     * @return Map that consists of FQDN (Fully Qualified Domain Name) and corresponding
     * scanResults per network type({@link WifiManager#PASSPOINT_HOME_NETWORK} and {@link
     * WifiManager#PASSPOINT_ROAMING_NETWORK}).
     */
    @Override
    public Map<String, Map<Integer, List<ScanResult>>> getAllMatchingFqdnsForScanResults(
            List<ScanResult> scanResults) {
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getMatchingPasspointConfigurations uid=%").c(Binder.getCallingUid()).flush();
        }
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_PASSPOINT)) {
            return new HashMap<>();
        }
        return mClientModeImpl.syncGetAllMatchingFqdnsForScanResults(scanResults,
                mClientModeImplChannel);
    }

    /**
     * Returns list of OSU (Online Sign-Up) providers associated with the given list of ScanResult.
     *
     * @param scanResults a list of ScanResult that has Passpoint APs.
     * @return Map that consists of {@link OsuProvider} and a matching list of {@link ScanResult}.
     */
    @Override
    public Map<OsuProvider, List<ScanResult>> getMatchingOsuProviders(
            List<ScanResult> scanResults) {
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getMatchingOsuProviders uid=%").c(Binder.getCallingUid()).flush();
        }
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_PASSPOINT)) {
            return new HashMap<>();
        }
        return mClientModeImpl.syncGetMatchingOsuProviders(scanResults, mClientModeImplChannel);
    }

    /**
     * Returns the matching Passpoint configurations for given OSU(Online Sign-Up) providers.
     *
     * @param osuProviders a list of {@link OsuProvider}
     * @return Map that consists of {@link OsuProvider} and matching {@link PasspointConfiguration}.
     */
    @Override
    public Map<OsuProvider, PasspointConfiguration> getMatchingPasspointConfigsForOsuProviders(
            List<OsuProvider> osuProviders) {
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getMatchingPasspointConfigsForOsuProviders uid=%").c(
                    Binder.getCallingUid()).flush();
        }
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_PASSPOINT)) {
            return new HashMap<>();
        }
        if (osuProviders == null) {
            Log.e(TAG, "Attempt to retrieve Passpoint configuration with null osuProviders");
            return new HashMap<>();
        }
        return mClientModeImpl.syncGetMatchingPasspointConfigsForOsuProviders(osuProviders,
                mClientModeImplChannel);
    }

    /**
     * Returns the corresponding wifi configurations for given FQDN (Fully Qualified Domain Name)
     * list.
     *
     * An empty list will be returned when no match is found.
     *
     * @param fqdnList a list of FQDN
     * @return List of {@link WifiConfiguration} converted from {@link PasspointProvider}
     */
    @Override
    public List<WifiConfiguration> getWifiConfigsForPasspointProfiles(List<String> fqdnList) {
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getWifiConfigsForPasspointProfiles uid=%").c(
                    Binder.getCallingUid()).flush();
        }
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_PASSPOINT)) {
            return new ArrayList<>();
        }
        if (fqdnList == null) {
            Log.e(TAG, "Attempt to retrieve WifiConfiguration with null fqdn List");
            return new ArrayList<>();
        }
        return mClientModeImpl.syncGetWifiConfigsForPasspointProfiles(fqdnList,
                mClientModeImplChannel);
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
        if (!isTargetSdkLessThanQOrPrivileged(
                packageName, Binder.getCallingPid(), Binder.getCallingUid())) {
            mLog.info("addOrUpdateNetwork not allowed for uid=%")
                    .c(Binder.getCallingUid()).flush();
            return -1;
        }
        mLog.info("addOrUpdateNetwork uid=%").c(Binder.getCallingUid()).flush();

        if (config == null) {
            Slog.e(TAG, "bad network configuration");
            return -1;
        }
        mWifiMetrics.incrementNumAddOrUpdateNetworkCalls();

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
            X509Certificate[] x509Certificates = null;
            if (config.enterpriseConfig.getCaCertificate() != null) {
                x509Certificates =
                        new X509Certificate[]{config.enterpriseConfig.getCaCertificate()};
            }
            passpointConfig.getCredential().setCaCertificates(x509Certificates);
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

        //TODO: pass the Uid the ClientModeImpl as a message parameter
        Slog.i("addOrUpdateNetwork", " uid = " + Integer.toString(Binder.getCallingUid())
                + " SSID " + config.SSID
                + " nid=" + Integer.toString(config.networkId));
        if (config.networkId == WifiConfiguration.INVALID_NETWORK_ID) {
            config.creatorUid = Binder.getCallingUid();
        } else {
            config.lastUpdateUid = Binder.getCallingUid();
        }
        if (mClientModeImplChannel != null) {
            return mClientModeImpl.syncAddOrUpdateNetwork(mClientModeImplChannel, config);
        } else {
            Slog.e(TAG, "mClientModeImplChannel is not initialized");
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
        if (!isTargetSdkLessThanQOrPrivileged(
                packageName, Binder.getCallingPid(), Binder.getCallingUid())) {
            mLog.info("removeNetwork not allowed for uid=%")
                    .c(Binder.getCallingUid()).flush();
            return false;
        }
        mLog.info("removeNetwork uid=%").c(Binder.getCallingUid()).flush();
        // TODO Add private logging for netId b/33807876
        if (mClientModeImplChannel != null) {
            return mClientModeImpl.syncRemoveNetwork(mClientModeImplChannel, netId);
        } else {
            Slog.e(TAG, "mClientModeImplChannel is not initialized");
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
        if (!isTargetSdkLessThanQOrPrivileged(
                packageName, Binder.getCallingPid(), Binder.getCallingUid())) {
            mLog.info("enableNetwork not allowed for uid=%")
                    .c(Binder.getCallingUid()).flush();
            return false;
        }
        // TODO b/33807876 Log netId
        mLog.info("enableNetwork uid=% disableOthers=%")
                .c(Binder.getCallingUid())
                .c(disableOthers).flush();

        mWifiMetrics.incrementNumEnableNetworkCalls();
        if (mClientModeImplChannel != null) {
            return mClientModeImpl.syncEnableNetwork(mClientModeImplChannel, netId,
                    disableOthers);
        } else {
            Slog.e(TAG, "mClientModeImplChannel is not initialized");
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
        if (!isTargetSdkLessThanQOrPrivileged(
                packageName, Binder.getCallingPid(), Binder.getCallingUid())) {
            mLog.info("disableNetwork not allowed for uid=%")
                    .c(Binder.getCallingUid()).flush();
            return false;
        }
        // TODO b/33807876 Log netId
        mLog.info("disableNetwork uid=%").c(Binder.getCallingUid()).flush();

        if (mClientModeImplChannel != null) {
            return mClientModeImpl.syncDisableNetwork(mClientModeImplChannel, netId);
        } else {
            Slog.e(TAG, "mClientModeImplChannel is not initialized");
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
            WifiInfo result = mClientModeImpl.syncRequestConnectionInfo();
            boolean hideDefaultMacAddress = true;
            boolean hideBssidSsidAndNetworkId = true;

            try {
                if (mWifiInjector.getWifiPermissionsWrapper().getLocalMacAddressPermission(uid)
                        == PERMISSION_GRANTED) {
                    hideDefaultMacAddress = false;
                }
                mWifiPermissionsUtil.enforceCanAccessScanResults(callingPackage, uid);
                hideBssidSsidAndNetworkId = false;
            } catch (RemoteException e) {
                Log.e(TAG, "Error checking receiver permission", e);
            } catch (SecurityException e) {
            }
            if (hideDefaultMacAddress) {
                result.setMacAddress(WifiInfo.DEFAULT_MAC_ADDRESS);
            }
            if (hideBssidSsidAndNetworkId) {
                result.setBSSID(WifiInfo.DEFAULT_MAC_ADDRESS);
                result.setSSID(WifiSsid.createFromHex(null));
                result.setNetworkId(WifiConfiguration.INVALID_NETWORK_ID);
            }
            if (mVerboseLoggingEnabled && (hideBssidSsidAndNetworkId || hideDefaultMacAddress)) {
                mLog.v("getConnectionInfo: hideBssidSsidAndNetworkId="
                        + hideBssidSsidAndNetworkId
                        + ", hideDefaultMacAddress="
                        + hideDefaultMacAddress);
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
            boolean success = mWifiInjector.getClientModeImplHandler().runWithScissors(() -> {
                scanResults.addAll(mScanRequestProxy.getScanResults());
            }, RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
            if (!success) {
                Log.e(TAG, "Failed to post runnable to fetch scan results");
                return new ArrayList<ScanResult>();
            }
            return scanResults;
        } catch (SecurityException e) {
            Slog.e(TAG, "Permission violation - getScanResults not allowed for uid="
                    + uid + ", packageName=" + callingPackage + ", reason=" + e);
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
            return false;
        }
        return mClientModeImpl.syncAddOrUpdatePasspointConfig(mClientModeImplChannel, config,
                Binder.getCallingUid(), packageName);
    }

    /**
     * Remove the Passpoint configuration identified by its FQDN (Fully Qualified Domain Name).
     *
     * @param fqdn The FQDN of the Passpoint configuration to be removed
     * @return true on success or false on failure
     */
    @Override
    public boolean removePasspointConfiguration(String fqdn, String packageName) {
        final int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                && !mWifiPermissionsUtil.checkNetworkCarrierProvisioningPermission(uid)) {
            if (mWifiPermissionsUtil.isTargetSdkLessThan(packageName, Build.VERSION_CODES.Q)) {
                return false;
            }
            throw new SecurityException(TAG + ": Permission denied");
        }
        mLog.info("removePasspointConfiguration uid=%").c(Binder.getCallingUid()).flush();
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_PASSPOINT)) {
            return false;
        }
        return mClientModeImpl.syncRemovePasspointConfig(mClientModeImplChannel, fqdn);
    }

    /**
     * Return the list of the installed Passpoint configurations.
     *
     * An empty list will be returned when no configuration is installed.
     * @param packageName String name of the calling package
     * @return A list of {@link PasspointConfiguration}.
     */
    @Override
    public List<PasspointConfiguration> getPasspointConfigurations(String packageName) {
        final int uid = Binder.getCallingUid();
        mAppOps.checkPackage(uid, packageName);
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                && !mWifiPermissionsUtil.checkNetworkSetupWizardPermission(uid)) {
            if (mWifiPermissionsUtil.isTargetSdkLessThan(packageName, Build.VERSION_CODES.Q)) {
                return new ArrayList<>();
            }
            throw new SecurityException(TAG + ": Permission denied");
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("getPasspointConfigurations uid=%").c(Binder.getCallingUid()).flush();
        }
        if (!mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_PASSPOINT)) {
            return new ArrayList<>();
        }
        return mClientModeImpl.syncGetPasspointConfigs(mClientModeImplChannel);
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
        mClientModeImpl.syncQueryPasspointIcon(mClientModeImplChannel, bssid, fileName);
    }

    /**
     * Match the currently associated network against the SP matching the given FQDN
     * @param fqdn FQDN of the SP
     * @return ordinal [HomeProvider, RoamingProvider, Incomplete, None, Declined]
     */
    @Override
    public int matchProviderWithCurrentNetwork(String fqdn) {
        mLog.info("matchProviderWithCurrentNetwork uid=%").c(Binder.getCallingUid()).flush();
        return mClientModeImpl.matchProviderWithCurrentNetwork(mClientModeImplChannel, fqdn);
    }

    /**
     * Deauthenticate and set the re-authentication hold off time for the current network
     * @param holdoff hold off time in milliseconds
     * @param ess set if the hold off pertains to an ESS rather than a BSS
     */
    @Override
    public void deauthenticateNetwork(long holdoff, boolean ess) {
        mLog.info("deauthenticateNetwork uid=%").c(Binder.getCallingUid()).flush();
        mClientModeImpl.deauthenticateNetwork(mClientModeImplChannel, holdoff, ess);
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
        //TODO (b/123227116): pull it from the HAL
        if (mVerboseLoggingEnabled) {
            mLog.info("isDualBandSupported uid=%").c(Binder.getCallingUid()).flush();
        }

        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_wifi_dual_band_support);
    }

    private int getMaxApInterfacesCount() {
        //TODO (b/123227116): pull it from the HAL
        return mContext.getResources().getInteger(
                com.android.internal.R.integer.config_wifi_max_ap_interfaces);
    }

    private boolean isConcurrentLohsAndTetheringSupported() {
        // TODO(b/110697252): handle all configurations in the wifi stack (just by changing the HAL)
        return getMaxApInterfacesCount() >= 2;
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
        DhcpResults dhcpResults = mClientModeImpl.syncGetDhcpResults();

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

        mClientModeImpl.enableTdls(remoteMacAddress, enable);
    }

    /**
     * Get a reference to handler. This is used by a client to establish
     * an AsyncChannel communication with WifiService
     */
    @Override
    public Messenger getWifiServiceMessenger(String packageName) {
        enforceAccessPermission();
        if (enforceChangePermission(packageName) != MODE_ALLOWED) {
            // We don't have a good way of creating a fake Messenger, and returning null would
            // immediately break callers.
            throw new SecurityException("Could not create wifi service messenger");
        }
        mLog.info("getWifiServiceMessenger uid=%").c(Binder.getCallingUid()).flush();
        return new Messenger(mAsyncChannelExternalClientHandler);
    }

    /**
     * Disable an ephemeral network, i.e. network that is created thru a WiFi Scorer
     */
    @Override
    public void disableEphemeralNetwork(String SSID, String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE,
                "WifiService");
        if (!isPrivileged(Binder.getCallingPid(), Binder.getCallingUid())) {
            mLog.info("disableEphemeralNetwork not allowed for uid=%")
                    .c(Binder.getCallingUid()).flush();
            return;
        }
        mLog.info("disableEphemeralNetwork uid=%").c(Binder.getCallingUid()).flush();
        mClientModeImpl.disableEphemeralNetwork(SSID);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_USER_REMOVED)) {
                int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                mClientModeImpl.removeUserConfigs(userHandle);
            } else if (action.equals(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                        BluetoothAdapter.STATE_DISCONNECTED);
                mClientModeImpl.sendBluetoothAdapterStateChange(state);
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
                    mClientModeImpl.removeAppConfigs(pkgName, uid);

                    // Call the method in ClientModeImpl thread.
                    mWifiInjector.getClientModeImplHandler().post(() -> {
                        mScanRequestProxy.clearScanRequestTimestampsForApp(pkgName, uid);

                        // Remove all suggestions from the package.
                        mWifiNetworkSuggestionsManager.removeApp(pkgName);
                        mClientModeImpl.removeNetworkRequestUserApprovedAccessPointsForApp(pkgName);

                        // Remove all Passpoint profiles from package.
                        mWifiInjector.getPasspointManager().removePasspointProviderWithPackage(
                                pkgName);

                    });
                }
            }
        }, intentFilter);
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        (new WifiShellCommand(mWifiInjector)).exec(this, in, out, err,
                args, callback, resultReceiver);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump WifiService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        if (args != null && args.length > 0 && WifiMetrics.PROTO_DUMP_ARG.equals(args[0])) {
            // WifiMetrics proto bytes were requested. Dump only these.
            mClientModeImpl.updateWifiMetrics();
            mWifiMetrics.dump(fd, pw, args);
        } else if (args != null && args.length > 0 && IpClientUtil.DUMP_ARG.equals(args[0])) {
            // IpClient dump was requested. Pass it along and take no further action.
            String[] ipClientArgs = new String[args.length - 1];
            System.arraycopy(args, 1, ipClientArgs, 0, ipClientArgs.length);
            mClientModeImpl.dumpIpClient(fd, pw, ipClientArgs);
        } else if (args != null && args.length > 0 && WifiScoreReport.DUMP_ARG.equals(args[0])) {
            WifiScoreReport wifiScoreReport = mClientModeImpl.getWifiScoreReport();
            if (wifiScoreReport != null) wifiScoreReport.dump(fd, pw, args);
        } else if (args != null && args.length > 0 && WifiScoreCard.DUMP_ARG.equals(args[0])) {
            mWifiInjector.getClientModeImplHandler().runWithScissors(() -> {
                WifiScoreCard wifiScoreCard = mWifiInjector.getWifiScoreCard();
                if (wifiScoreCard != null) {
                    pw.println(wifiScoreCard.getNetworkListBase64(true));
                }
            }, RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
        } else {
            // Polls link layer stats and RSSI. This allows the stats to show up in
            // WifiScoreReport's dump() output when taking a bug report even if the screen is off.
            mClientModeImpl.updateLinkLayerStatsRssiAndScoreReport();
            pw.println("Wi-Fi is " + mClientModeImpl.syncGetWifiStateByName());
            pw.println("Verbose logging is " + (mVerboseLoggingEnabled ? "on" : "off"));
            pw.println("Stay-awake conditions: " +
                    mFacade.getIntegerSetting(mContext,
                            Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0));
            pw.println("mInIdleMode " + mInIdleMode);
            pw.println("mScanPending " + mScanPending);
            mWifiController.dump(fd, pw, args);
            mSettingsStore.dump(fd, pw, args);
            mWifiTrafficPoller.dump(fd, pw, args);
            pw.println();
            pw.println("Locks held:");
            mWifiLockManager.dump(pw);
            pw.println();
            mWifiMulticastLockManager.dump(pw);
            pw.println();
            mActiveModeWarden.dump(fd, pw, args);
            pw.println();
            mClientModeImpl.dump(fd, pw, args);
            pw.println();
            mWifiInjector.getClientModeImplHandler().runWithScissors(() -> {
                WifiScoreCard wifiScoreCard = mWifiInjector.getWifiScoreCard();
                if (wifiScoreCard != null) {
                    pw.println("WifiScoreCard:");
                    pw.println(wifiScoreCard.getNetworkListBase64(true));
                }
            }, RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
            mClientModeImpl.updateWifiMetrics();
            mWifiMetrics.dump(fd, pw, args);
            pw.println();
            mWifiInjector.getClientModeImplHandler().runWithScissors(() -> {
                mWifiNetworkSuggestionsManager.dump(fd, pw, args);
                pw.println();
            }, RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
            mWifiBackupRestore.dump(fd, pw, args);
            pw.println();
            pw.println("ScoringParams: settings put global " + Settings.Global.WIFI_SCORE_PARAMS
                       + " " + mWifiInjector.getScoringParams());
            pw.println();
            WifiScoreReport wifiScoreReport = mClientModeImpl.getWifiScoreReport();
            if (wifiScoreReport != null) {
                pw.println("WifiScoreReport:");
                wifiScoreReport.dump(fd, pw, args);
            }
            pw.println();
            SarManager sarManager = mWifiInjector.getSarManager();
            if (sarManager != null) {
                sarManager.dump(fd, pw, args);
            }
            pw.println();
        }
    }

    @Override
    public boolean acquireWifiLock(IBinder binder, int lockMode, String tag, WorkSource ws) {
        mLog.info("acquireWifiLock uid=% lockMode=%")
                .c(Binder.getCallingUid())
                .c(lockMode).flush();

        // Check on permission to make this call
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);

        // If no UID is provided in worksource, use the calling UID
        WorkSource updatedWs = (ws == null || ws.isEmpty())
                ? new WorkSource(Binder.getCallingUid()) : ws;

        Mutable<Boolean> lockSuccess = new Mutable<>();
        boolean runWithScissorsSuccess = mWifiInjector.getClientModeImplHandler().runWithScissors(
                () -> {
                    lockSuccess.value = mWifiLockManager.acquireWifiLock(
                            lockMode, tag, binder, updatedWs);
                }, RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
        if (!runWithScissorsSuccess) {
            Log.e(TAG, "Failed to post runnable to acquireWifiLock");
            return false;
        }

        return lockSuccess.value;
    }

    @Override
    public void updateWifiLockWorkSource(IBinder binder, WorkSource ws) {
        mLog.info("updateWifiLockWorkSource uid=%").c(Binder.getCallingUid()).flush();

        // Check on permission to make this call
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.UPDATE_DEVICE_STATS, null);

        // If no UID is provided in worksource, use the calling UID
        WorkSource updatedWs = (ws == null || ws.isEmpty())
                ? new WorkSource(Binder.getCallingUid()) : ws;

        boolean runWithScissorsSuccess = mWifiInjector.getClientModeImplHandler().runWithScissors(
                () -> {
                    mWifiLockManager.updateWifiLockWorkSource(binder, updatedWs);
                }, RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
        if (!runWithScissorsSuccess) {
            Log.e(TAG, "Failed to post runnable to updateWifiLockWorkSource");
        }
    }

    @Override
    public boolean releaseWifiLock(IBinder binder) {
        mLog.info("releaseWifiLock uid=%").c(Binder.getCallingUid()).flush();

        // Check on permission to make this call
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.WAKE_LOCK, null);
        Mutable<Boolean> lockSuccess = new Mutable<>();
        boolean runWithScissorsSuccess = mWifiInjector.getClientModeImplHandler().runWithScissors(
                () -> {
                    lockSuccess.value = mWifiLockManager.releaseWifiLock(binder);
                }, RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
        if (!runWithScissorsSuccess) {
            Log.e(TAG, "Failed to post runnable to releaseWifiLock");
            return false;
        }
        return lockSuccess.value;
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
    public void releaseMulticastLock(String tag) {
        enforceMulticastChangePermission();
        mLog.info("releaseMulticastLock uid=%").c(Binder.getCallingUid()).flush();
        mWifiMulticastLockManager.releaseLock(tag);
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
        mClientModeImpl.enableVerboseLogging(verbose);
        mWifiLockManager.enableVerboseLogging(verbose);
        mWifiMulticastLockManager.enableVerboseLogging(verbose);
        mWifiInjector.enableVerboseLogging(verbose);
    }

    @Override
    public int getVerboseLoggingLevel() {
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
            // Turn mobile hotspot off
            stopSoftApInternal(WifiManager.IFACE_IP_MODE_UNSPECIFIED);
        }

        if (!mUserManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_WIFI)) {
            if (mClientModeImplChannel != null) {
                // Delete all Wifi SSIDs
                List<WifiConfiguration> networks = mClientModeImpl.syncGetConfiguredNetworks(
                        Binder.getCallingUid(), mClientModeImplChannel, Process.WIFI_UID);
                if (networks != null) {
                    for (WifiConfiguration config : networks) {
                        removeNetwork(config.networkId, packageName);
                    }
                }

                // Delete all Passpoint configurations
                if (mContext.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_WIFI_PASSPOINT)) {
                    List<PasspointConfiguration> configs = mClientModeImpl.syncGetPasspointConfigs(
                            mClientModeImplChannel);
                    if (configs != null) {
                        for (PasspointConfiguration config : configs) {
                            removePasspointConfiguration(config.getHomeSp().getFqdn(), packageName);
                        }
                    }
                }
            }

            mWifiInjector.getClientModeImplHandler().post(() -> {
                mWifiInjector.getWifiConfigManager().clearDeletedEphemeralNetworks();
                mClientModeImpl.clearNetworkRequestUserApprovedAccessPoints();
                mWifiNetworkSuggestionsManager.clear();
                mWifiInjector.getWifiScoreCard().clear();
            });
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
        return mClientModeImpl.getCurrentNetwork();
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
        mClientModeImpl.enableWifiConnectivityManager(enabled);
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
        if (mClientModeImplChannel == null) {
            Slog.e(TAG, "mClientModeImplChannel is not initialized");
            return null;
        }

        Slog.d(TAG, "Retrieving backup data");
        List<WifiConfiguration> wifiConfigurations =
                mClientModeImpl.syncGetPrivilegedConfiguredNetwork(mClientModeImplChannel);
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
            int networkId = mClientModeImpl.syncAddOrUpdateNetwork(
                    mClientModeImplChannel, configuration);
            if (networkId == WifiConfiguration.INVALID_NETWORK_ID) {
                Slog.e(TAG, "Restore network failed: " + configuration.configKey());
                continue;
            }
            // Enable all networks restored.
            mClientModeImpl.syncEnableNetwork(mClientModeImplChannel, networkId, false);
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
        if (mClientModeImplChannel == null) {
            Slog.e(TAG, "mClientModeImplChannel is not initialized");
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
        if (mClientModeImplChannel == null) {
            Slog.e(TAG, "mClientModeImplChannel is not initialized");
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
     * Starts subscription provisioning with a provider.
     *
     * @param provider {@link OsuProvider} the provider to provision with
     * @param callback {@link IProvisioningCallback} the callback object to inform status
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
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        if (!mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_PASSPOINT)) {
            throw new UnsupportedOperationException("Passpoint not enabled");
        }
        final int uid = Binder.getCallingUid();
        mLog.trace("startSubscriptionProvisioning uid=%").c(uid).flush();
        if (mClientModeImpl.syncStartSubscriptionProvisioning(uid, provider,
                callback, mClientModeImplChannel)) {
            mLog.trace("Subscription provisioning started with %")
                    .c(provider.toString()).flush();
        }
    }

    /**
     * see {@link android.net.wifi.WifiManager#registerTrafficStateCallback(
     * WifiManager.TrafficStateCallback, Handler)}
     *
     * @param binder IBinder instance to allow cleanup if the app dies
     * @param callback Traffic State callback to register
     * @param callbackIdentifier Unique ID of the registering callback. This ID will be used to
     *        unregister the callback. See {@link unregisterTrafficStateCallback(int)}
     *
     * @throws SecurityException if the caller does not have permission to register a callback
     * @throws RemoteException if remote exception happens
     * @throws IllegalArgumentException if the arguments are null or invalid
     */
    @Override
    public void registerTrafficStateCallback(IBinder binder, ITrafficStateCallback callback,
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
            mLog.info("registerTrafficStateCallback uid=%").c(Binder.getCallingUid()).flush();
        }
        // Post operation to handler thread
        mWifiInjector.getClientModeImplHandler().post(() -> {
            mWifiTrafficPoller.addCallback(binder, callback, callbackIdentifier);
        });
    }

    /**
     * see {@link android.net.wifi.WifiManager#unregisterTrafficStateCallback(
     * WifiManager.TrafficStateCallback)}
     *
     * @param callbackIdentifier Unique ID of the callback to be unregistered.
     *
     * @throws SecurityException if the caller does not have permission to register a callback
     */
    @Override
    public void unregisterTrafficStateCallback(int callbackIdentifier) {
        enforceNetworkSettingsPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("unregisterTrafficStateCallback uid=%").c(Binder.getCallingUid()).flush();
        }
        // Post operation to handler thread
        mWifiInjector.getClientModeImplHandler().post(() -> {
            mWifiTrafficPoller.removeCallback(callbackIdentifier);
        });
    }

    private boolean is5GhzSupported() {
        return (getSupportedFeaturesInternal() & WIFI_FEATURE_INFRA_5G) == WIFI_FEATURE_INFRA_5G;
    }

    private long getSupportedFeaturesInternal() {
        final AsyncChannel channel = mClientModeImplChannel;
        if (channel != null) {
            return mClientModeImpl.syncGetSupportedFeatures(channel);
        } else {
            Slog.e(TAG, "mClientModeImplChannel is not initialized");
            return 0;
        }
    }

    private static boolean hasAutomotiveFeature(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    /**
     * see {@link android.net.wifi.WifiManager#registerNetworkRequestMatchCallback(
     * WifiManager.NetworkRequestMatchCallback, Handler)} (
     *
     * @param binder IBinder instance to allow cleanup if the app dies
     * @param callback Network Request Match callback to register
     * @param callbackIdentifier Unique ID of the registering callback. This ID will be used to
     *                           unregister the callback.
     *                           See {@link #unregisterNetworkRequestMatchCallback(int)} (int)}
     *
     * @throws SecurityException if the caller does not have permission to register a callback
     * @throws RemoteException if remote exception happens
     * @throws IllegalArgumentException if the arguments are null or invalid
     */
    @Override
    public void registerNetworkRequestMatchCallback(IBinder binder,
                                                    INetworkRequestMatchCallback callback,
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
            mLog.info("registerNetworkRequestMatchCallback uid=%")
                    .c(Binder.getCallingUid()).flush();
        }
        // Post operation to handler thread
        mWifiInjector.getClientModeImplHandler().post(() -> {
            mClientModeImpl.addNetworkRequestMatchCallback(binder, callback, callbackIdentifier);
        });
    }

    /**
     * see {@link android.net.wifi.WifiManager#unregisterNetworkRequestMatchCallback(
     * WifiManager.NetworkRequestMatchCallback)}
     *
     * @param callbackIdentifier Unique ID of the callback to be unregistered.
     *
     * @throws SecurityException if the caller does not have permission to register a callback
     */
    @Override
    public void unregisterNetworkRequestMatchCallback(int callbackIdentifier) {
        enforceNetworkSettingsPermission();
        if (mVerboseLoggingEnabled) {
            mLog.info("unregisterNetworkRequestMatchCallback uid=%")
                    .c(Binder.getCallingUid()).flush();
        }
        // Post operation to handler thread
        mWifiInjector.getClientModeImplHandler().post(() -> {
            mClientModeImpl.removeNetworkRequestMatchCallback(callbackIdentifier);
        });
    }

    /**
     * See {@link android.net.wifi.WifiManager#addNetworkSuggestions(List)}
     *
     * @param networkSuggestions List of network suggestions to be added.
     * @param callingPackageName Package Name of the app adding the suggestions.
     * @throws SecurityException if the caller does not have permission.
     * @return One of status codes from {@link WifiManager.NetworkSuggestionsStatusCode}.
     */
    @Override
    public int addNetworkSuggestions(
            List<WifiNetworkSuggestion> networkSuggestions, String callingPackageName) {
        if (enforceChangePermission(callingPackageName) != MODE_ALLOWED) {
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED;
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("addNetworkSuggestions uid=%").c(Binder.getCallingUid()).flush();
        }
        int callingUid = Binder.getCallingUid();
        Mutable<Integer> success = new Mutable<>();
        boolean runWithScissorsSuccess = mWifiInjector.getClientModeImplHandler().runWithScissors(
                () -> {
                    success.value = mWifiNetworkSuggestionsManager.add(
                            networkSuggestions, callingUid, callingPackageName);
                }, RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
        if (!runWithScissorsSuccess) {
            Log.e(TAG, "Failed to post runnable to add network suggestions");
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL;
        }
        if (success.value != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Log.e(TAG, "Failed to add network suggestions");
        }
        return success.value;
    }

    /**
     * See {@link android.net.wifi.WifiManager#removeNetworkSuggestions(List)}
     *
     * @param networkSuggestions List of network suggestions to be removed.
     * @param callingPackageName Package Name of the app removing the suggestions.
     * @throws SecurityException if the caller does not have permission.
     * @return One of status codes from {@link WifiManager.NetworkSuggestionsStatusCode}.
     */
    @Override
    public int removeNetworkSuggestions(
            List<WifiNetworkSuggestion> networkSuggestions, String callingPackageName) {
        if (enforceChangePermission(callingPackageName) != MODE_ALLOWED) {
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED;
        }
        if (mVerboseLoggingEnabled) {
            mLog.info("removeNetworkSuggestions uid=%").c(Binder.getCallingUid()).flush();
        }
        Mutable<Integer> success = new Mutable<>();
        boolean runWithScissorsSuccess = mWifiInjector.getClientModeImplHandler().runWithScissors(
                () -> {
                    success.value = mWifiNetworkSuggestionsManager.remove(
                            networkSuggestions, callingPackageName);
                }, RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
        if (!runWithScissorsSuccess) {
            Log.e(TAG, "Failed to post runnable to remove network suggestions");
            return WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL;
        }
        if (success.value != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Log.e(TAG, "Failed to remove network suggestions");
        }
        return success.value;
    }

    /**
     * Gets the factory Wi-Fi MAC addresses.
     * @throws SecurityException if the caller does not have permission.
     * @return Array of String representing Wi-Fi MAC addresses, or null if failed.
     */
    @Override
    public String[] getFactoryMacAddresses() {
        final int uid = Binder.getCallingUid();
        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
            throw new SecurityException("App not allowed to get Wi-Fi factory MAC address "
                    + "(uid = " + uid + ")");
        }
        final List<String> result = new ArrayList<>();
        boolean success = mWifiInjector.getClientModeImplHandler().runWithScissors(() -> {
            final String mac = mClientModeImpl.getFactoryMacAddress();
            if (mac != null) {
                result.add(mac);
            }
        }, RUN_WITH_SCISSORS_TIMEOUT_MILLIS);
        if (success) {
            return result.isEmpty() ? null : result.stream().toArray(String[]::new);
        }
        return null;
    }

    /**
     * Sets the current device mobility state.
     * @param state the new device mobility state
     */
    @Override
    public void setDeviceMobilityState(@DeviceMobilityState int state) {
        mContext.enforceCallingPermission(
                android.Manifest.permission.WIFI_SET_DEVICE_MOBILITY_STATE, "WifiService");

        if (mVerboseLoggingEnabled) {
            mLog.info("setDeviceMobilityState uid=% state=%")
                    .c(Binder.getCallingUid())
                    .c(state)
                    .flush();
        }
        // Post operation to handler thread
        mWifiInjector.getClientModeImplHandler().post(
                () -> mClientModeImpl.setDeviceMobilityState(state));
    }

    /**
     * Proxy for the final native call of the parent class. Enables mocking of
     * the function.
     */
    public int getMockableCallingUid() {
        return getCallingUid();
    }

    /**
     * Start DPP in Configurator-Initiator role. The current device will initiate DPP bootstrapping
     * with a peer, and send the SSID and password of the selected network.
     *
     * @param binder Caller's binder context
     * @param enrolleeUri URI of the Enrollee obtained externally (e.g. QR code scanning)
     * @param selectedNetworkId Selected network ID to be sent to the peer
     * @param netRole The network role of the enrollee
     * @param callback Callback for status updates
     */
    @Override
    public void startDppAsConfiguratorInitiator(IBinder binder, String enrolleeUri,
            int selectedNetworkId, int netRole, IDppCallback callback) {
        // verify arguments
        if (binder == null) {
            throw new IllegalArgumentException("Binder must not be null");
        }
        if (TextUtils.isEmpty(enrolleeUri)) {
            throw new IllegalArgumentException("Enrollee URI must not be null or empty");
        }
        if (selectedNetworkId < 0) {
            throw new IllegalArgumentException("Selected network ID invalid");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }

        final int uid = getMockableCallingUid();

        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }

        mDppManager.mHandler.post(() -> {
            mDppManager.startDppAsConfiguratorInitiator(uid, binder, enrolleeUri,
                    selectedNetworkId, netRole, callback);
        });
    }

    /**
     * Start DPP in Enrollee-Initiator role. The current device will initiate DPP bootstrapping
     * with a peer, and receive the SSID and password from the peer configurator.
     *
     * @param binder Caller's binder context
     * @param configuratorUri URI of the Configurator obtained externally (e.g. QR code scanning)
     * @param callback Callback for status updates
     */
    @Override
    public void startDppAsEnrolleeInitiator(IBinder binder, String configuratorUri,
            IDppCallback callback) {
        // verify arguments
        if (binder == null) {
            throw new IllegalArgumentException("Binder must not be null");
        }
        if (TextUtils.isEmpty(configuratorUri)) {
            throw new IllegalArgumentException("Enrollee URI must not be null or empty");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }

        final int uid = getMockableCallingUid();

        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }

        mDppManager.mHandler.post(() -> {
            mDppManager.startDppAsEnrolleeInitiator(uid, binder, configuratorUri, callback);
        });
    }

    /**
     * Stop or abort a current DPP session.
     */
    @Override
    public void stopDppSession() throws android.os.RemoteException {
        if (!isSettingsOrSuw(Binder.getCallingPid(), Binder.getCallingUid())) {
            throw new SecurityException(TAG + ": Permission denied");
        }
        final int uid = getMockableCallingUid();

        mDppManager.mHandler.post(() -> {
            mDppManager.stopDppSession(uid);
        });
    }

    /**
     * see {@link android.net.wifi.WifiManager#addOnWifiUsabilityStatsListener(Executor,
     * OnWifiUsabilityStatsListener)}
     *
     * @param binder IBinder instance to allow cleanup if the app dies
     * @param listener WifiUsabilityStatsEntry listener to add
     * @param listenerIdentifier Unique ID of the adding listener. This ID will be used to
     *        remove the listener. See {@link removeOnWifiUsabilityStatsListener(int)}
     *
     * @throws SecurityException if the caller does not have permission to add a listener
     * @throws RemoteException if remote exception happens
     * @throws IllegalArgumentException if the arguments are null or invalid
     */
    @Override
    public void addOnWifiUsabilityStatsListener(IBinder binder,
            IOnWifiUsabilityStatsListener listener, int listenerIdentifier) {
        // verify arguments
        if (binder == null) {
            throw new IllegalArgumentException("Binder must not be null");
        }
        if (listener == null) {
            throw new IllegalArgumentException("Listener must not be null");
        }
        mContext.enforceCallingPermission(
                android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE, "WifiService");
        if (mVerboseLoggingEnabled) {
            mLog.info("addOnWifiUsabilityStatsListener uid=%")
                .c(Binder.getCallingUid()).flush();
        }
        // Post operation to handler thread
        mWifiInjector.getClientModeImplHandler().post(() -> {
            mWifiMetrics.addOnWifiUsabilityListener(binder, listener, listenerIdentifier);
        });
    }

    /**
     * see {@link android.net.wifi.WifiManager#removeOnWifiUsabilityStatsListener(
     * OnWifiUsabilityStatsListener)}
     *
     * @param listenerIdentifier Unique ID of the listener to be removed.
     *
     * @throws SecurityException if the caller does not have permission to add a listener
     */
    @Override
    public void removeOnWifiUsabilityStatsListener(int listenerIdentifier) {
        mContext.enforceCallingPermission(
                android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE, "WifiService");
        if (mVerboseLoggingEnabled) {
            mLog.info("removeOnWifiUsabilityStatsListener uid=%")
                    .c(Binder.getCallingUid()).flush();
        }
        // Post operation to handler thread
        mWifiInjector.getClientModeImplHandler().post(() -> {
            mWifiMetrics.removeOnWifiUsabilityListener(listenerIdentifier);
        });
    }

    /**
     * Updates the Wi-Fi usability score.
     * @param seqNum Sequence number of the Wi-Fi usability score.
     * @param score The Wi-Fi usability score.
     * @param predictionHorizonSec Prediction horizon of the Wi-Fi usability score in second.
     */
    @Override
    public void updateWifiUsabilityScore(int seqNum, int score, int predictionHorizonSec) {
        mContext.enforceCallingPermission(
                android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE, "WifiService");

        if (mVerboseLoggingEnabled) {
            mLog.info("updateWifiUsabilityScore uid=% seqNum=% score=% predictionHorizonSec=%")
                    .c(Binder.getCallingUid())
                    .c(seqNum)
                    .c(score)
                    .c(predictionHorizonSec)
                    .flush();
        }
        // Post operation to handler thread
        mWifiInjector.getClientModeImplHandler().post(
                () -> mClientModeImpl.updateWifiUsabilityScore(seqNum, score,
                        predictionHorizonSec));
    }
}
