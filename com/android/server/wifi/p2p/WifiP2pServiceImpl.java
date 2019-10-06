/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.wifi.p2p;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.DhcpResults;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkUtils;
import android.net.ip.IIpClient;
import android.net.ip.IpClientCallbacks;
import android.net.ip.IpClientUtil;
import android.net.shared.ProvisioningConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.IWifiP2pManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pGroupList;
import android.net.wifi.p2p.WifiP2pGroupList.GroupDeleteListener;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pProvDiscEvent;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest;
import android.net.wifi.p2p.nsd.WifiP2pServiceResponse;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.FrameworkFacade;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.WifiLog;
import com.android.server.wifi.nano.WifiMetricsProto.P2pConnectionEvent;
import com.android.server.wifi.util.WifiAsyncChannel;
import com.android.server.wifi.util.WifiHandler;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.server.wifi.util.WifiPermissionsWrapper;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WifiP2pService includes a state machine to perform Wi-Fi p2p operations. Applications
 * communicate with this service to issue device discovery and connectivity requests
 * through the WifiP2pManager interface. The state machine communicates with the wifi
 * driver through wpa_supplicant and handles the event responses through WifiMonitor.
 *
 * Note that the term Wifi when used without a p2p suffix refers to the client mode
 * of Wifi operation
 * @hide
 */
public class WifiP2pServiceImpl extends IWifiP2pManager.Stub {
    private static final String TAG = "WifiP2pService";
    private boolean mVerboseLoggingEnabled = false;
    private static final String NETWORKTYPE = "WIFI_P2P";

    private Context mContext;

    INetworkManagementService mNwService;
    private IIpClient mIpClient;
    private int mIpClientStartIndex = 0;
    private DhcpResults mDhcpResults;

    private P2pStateMachine mP2pStateMachine;
    private AsyncChannel mReplyChannel = new WifiAsyncChannel(TAG);
    private AsyncChannel mWifiChannel;
    private LocationManager mLocationManager;
    private WifiInjector mWifiInjector;
    private WifiPermissionsUtil mWifiPermissionsUtil;
    private FrameworkFacade mFrameworkFacade;
    private WifiP2pMetrics mWifiP2pMetrics;

    private static final Boolean JOIN_GROUP = true;
    private static final Boolean FORM_GROUP = false;

    private static final Boolean RELOAD = true;
    private static final Boolean NO_RELOAD = false;

    private static final String[] RECEIVER_PERMISSIONS_FOR_BROADCAST = {
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_WIFI_STATE
    };

    // Two minutes comes from the wpa_supplicant setting
    private static final int GROUP_CREATING_WAIT_TIME_MS = 120 * 1000;
    private static int sGroupCreatingTimeoutIndex = 0;

    private static final int DISABLE_P2P_WAIT_TIME_MS = 5 * 1000;
    private static int sDisableP2pTimeoutIndex = 0;

    // Set a two minute discover timeout to avoid STA scans from being blocked
    private static final int DISCOVER_TIMEOUT_S = 120;

    // Idle time after a peer is gone when the group is torn down
    private static final int GROUP_IDLE_TIME_S = 10;

    private static final int BASE = Protocol.BASE_WIFI_P2P_SERVICE;

    // Delayed message to timeout group creation
    public static final int GROUP_CREATING_TIMED_OUT        =   BASE + 1;

    // User accepted a peer request
    private static final int PEER_CONNECTION_USER_ACCEPT    =   BASE + 2;
    // User rejected a peer request
    private static final int PEER_CONNECTION_USER_REJECT    =   BASE + 3;
    // User wants to disconnect wifi in favour of p2p
    private static final int DROP_WIFI_USER_ACCEPT          =   BASE + 4;
    // User wants to keep his wifi connection and drop p2p
    private static final int DROP_WIFI_USER_REJECT          =   BASE + 5;
    // Delayed message to timeout p2p disable
    public static final int DISABLE_P2P_TIMED_OUT           =   BASE + 6;
    // User confirm a peer request
    public static final int PEER_CONNECTION_USER_CONFIRM    =   BASE + 7;

    // Commands to the ClientModeImpl
    public static final int P2P_CONNECTION_CHANGED          =   BASE + 11;

    // These commands are used to temporarily disconnect wifi when we detect
    // a frequency conflict which would make it impossible to have with p2p
    // and wifi active at the same time.
    // If the user chooses to disable wifi temporarily, we keep wifi disconnected
    // until the p2p connection is done and terminated at which point we will
    // bring back wifi up
    // DISCONNECT_WIFI_REQUEST
    //      msg.arg1 = 1 enables temporary disconnect and 0 disables it.
    public static final int DISCONNECT_WIFI_REQUEST         =   BASE + 12;
    public static final int DISCONNECT_WIFI_RESPONSE        =   BASE + 13;

    public static final int SET_MIRACAST_MODE               =   BASE + 14;

    // During dhcp (and perhaps other times) we can't afford to drop packets
    // but Discovery will switch our channel enough we will.
    //   msg.arg1 = ENABLED for blocking, DISABLED for resumed.
    //   msg.arg2 = msg to send when blocked
    //   msg.obj  = StateMachine to send to when blocked
    public static final int BLOCK_DISCOVERY                 =   BASE + 15;
    public static final int ENABLE_P2P                      =   BASE + 16;
    public static final int DISABLE_P2P                     =   BASE + 17;
    public static final int REMOVE_CLIENT_INFO              =   BASE + 18;

    // Messages for interaction with IpClient.
    private static final int IPC_PRE_DHCP_ACTION            =   BASE + 30;
    private static final int IPC_POST_DHCP_ACTION           =   BASE + 31;
    private static final int IPC_DHCP_RESULTS               =   BASE + 32;
    private static final int IPC_PROVISIONING_SUCCESS       =   BASE + 33;
    private static final int IPC_PROVISIONING_FAILURE       =   BASE + 34;

    public static final int ENABLED                         = 1;
    public static final int DISABLED                        = 0;

    private final boolean mP2pSupported;

    private WifiP2pDevice mThisDevice = new WifiP2pDevice();

    // When a group has been explicitly created by an app, we persist the group
    // even after all clients have been disconnected until an explicit remove
    // is invoked
    private boolean mAutonomousGroup;

    // Invitation to join an existing p2p group
    private boolean mJoinExistingGroup;

    // Track whether we are in p2p discovery. This is used to avoid sending duplicate
    // broadcasts
    private boolean mDiscoveryStarted;

    // Track whether servcice/peer discovery is blocked in favor of other wifi actions
    // (notably dhcp)
    private boolean mDiscoveryBlocked;

    // remember if we were in a scan when it had to be stopped
    private boolean mDiscoveryPostponed = false;

    private NetworkInfo mNetworkInfo;

    private boolean mTemporarilyDisconnectedWifi = false;

    // The transaction Id of service discovery request
    private byte mServiceTransactionId = 0;

    // Service discovery request ID of wpa_supplicant.
    // null means it's not set yet.
    private String mServiceDiscReqId;

    // clients(application) information list
    private HashMap<Messenger, ClientInfo> mClientInfoList = new HashMap<Messenger, ClientInfo>();

    // clients(application) channel list
    private Map<IBinder, Messenger> mClientChannelList = new HashMap<IBinder, Messenger>();

    // Is chosen as a unique address to avoid conflict with
    // the ranges defined in Tethering.java
    private static final String SERVER_ADDRESS = "192.168.49.1";

    // The empty device address set by wpa_supplicant.
    private static final String EMPTY_DEVICE_ADDRESS = "00:00:00:00:00:00";

    // An anonymized device address. This is used instead of the own device MAC to prevent the
    // latter from leaking to apps
    private static final String ANONYMIZED_DEVICE_ADDRESS = "02:00:00:00:00:00";

    /**
     * Error code definition.
     * see the Table.8 in the WiFi Direct specification for the detail.
     */
    public enum P2pStatus {
        // Success
        SUCCESS,

        // The target device is currently unavailable
        INFORMATION_IS_CURRENTLY_UNAVAILABLE,

        // Protocol error
        INCOMPATIBLE_PARAMETERS,

        // The target device reached the limit of the number of the connectable device.
        // For example, device limit or group limit is set
        LIMIT_REACHED,

        // Protocol error
        INVALID_PARAMETER,

        // Unable to accommodate request
        UNABLE_TO_ACCOMMODATE_REQUEST,

        // Previous protocol error, or disruptive behavior
        PREVIOUS_PROTOCOL_ERROR,

        // There is no common channels the both devices can use
        NO_COMMON_CHANNEL,

        // Unknown p2p group. For example, Device A tries to invoke the previous persistent group,
        // but device B has removed the specified credential already
        UNKNOWN_P2P_GROUP,

        // Both p2p devices indicated an intent of 15 in group owner negotiation
        BOTH_GO_INTENT_15,

        // Incompatible provisioning method
        INCOMPATIBLE_PROVISIONING_METHOD,

        // Rejected by user
        REJECTED_BY_USER,

        // Unknown error
        UNKNOWN;

        /**
         * Returns P2p status corresponding to a given error value
         * @param error integer error value
         * @return P2pStatus enum for value
         */
        public static P2pStatus valueOf(int error) {
            switch(error) {
                case 0 :
                    return SUCCESS;
                case 1:
                    return INFORMATION_IS_CURRENTLY_UNAVAILABLE;
                case 2:
                    return INCOMPATIBLE_PARAMETERS;
                case 3:
                    return LIMIT_REACHED;
                case 4:
                    return INVALID_PARAMETER;
                case 5:
                    return UNABLE_TO_ACCOMMODATE_REQUEST;
                case 6:
                    return PREVIOUS_PROTOCOL_ERROR;
                case 7:
                    return NO_COMMON_CHANNEL;
                case 8:
                    return UNKNOWN_P2P_GROUP;
                case 9:
                    return BOTH_GO_INTENT_15;
                case 10:
                    return INCOMPATIBLE_PROVISIONING_METHOD;
                case 11:
                    return REJECTED_BY_USER;
                default:
                    return UNKNOWN;
            }
        }
    }

    /**
     * Handles client connections
     */
    private class ClientHandler extends WifiHandler {

        ClientHandler(String tag, android.os.Looper looper) {
            super(tag, looper);
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case WifiP2pManager.SET_DEVICE_NAME:
                case WifiP2pManager.SET_WFD_INFO:
                case WifiP2pManager.DISCOVER_PEERS:
                case WifiP2pManager.STOP_DISCOVERY:
                case WifiP2pManager.CONNECT:
                case WifiP2pManager.CANCEL_CONNECT:
                case WifiP2pManager.CREATE_GROUP:
                case WifiP2pManager.REMOVE_GROUP:
                case WifiP2pManager.START_LISTEN:
                case WifiP2pManager.STOP_LISTEN:
                case WifiP2pManager.SET_CHANNEL:
                case WifiP2pManager.START_WPS:
                case WifiP2pManager.ADD_LOCAL_SERVICE:
                case WifiP2pManager.REMOVE_LOCAL_SERVICE:
                case WifiP2pManager.CLEAR_LOCAL_SERVICES:
                case WifiP2pManager.DISCOVER_SERVICES:
                case WifiP2pManager.ADD_SERVICE_REQUEST:
                case WifiP2pManager.REMOVE_SERVICE_REQUEST:
                case WifiP2pManager.CLEAR_SERVICE_REQUESTS:
                case WifiP2pManager.REQUEST_PEERS:
                case WifiP2pManager.REQUEST_CONNECTION_INFO:
                case WifiP2pManager.REQUEST_GROUP_INFO:
                case WifiP2pManager.DELETE_PERSISTENT_GROUP:
                case WifiP2pManager.REQUEST_PERSISTENT_GROUP_INFO:
                case WifiP2pManager.FACTORY_RESET:
                case WifiP2pManager.SET_ONGOING_PEER_CONFIG:
                case WifiP2pManager.REQUEST_ONGOING_PEER_CONFIG:
                case WifiP2pManager.REQUEST_P2P_STATE:
                case WifiP2pManager.REQUEST_DISCOVERY_STATE:
                case WifiP2pManager.REQUEST_NETWORK_INFO:
                case WifiP2pManager.UPDATE_CHANNEL_INFO:
                case WifiP2pManager.REQUEST_DEVICE_INFO:
                    mP2pStateMachine.sendMessage(Message.obtain(msg));
                    break;
                default:
                    Slog.d(TAG, "ClientHandler.handleMessage ignoring msg=" + msg);
                    break;
            }
        }
    }
    private ClientHandler mClientHandler;

    /**
     * Provide a way for unit tests to set valid log object in the WifiHandler
     * @param log WifiLog object to assign to the clientHandler
     */
    @VisibleForTesting
    void setWifiHandlerLogForTest(WifiLog log) {
        mClientHandler.setWifiLog(log);

    }

    /**
     * Provide a way for unit tests to set valid log object in the WifiAsyncChannel
     * @param log WifiLog object to assign to the mReplyChannel
     */
    @VisibleForTesting
    void setWifiLogForReplyChannel(WifiLog log) {
        ((WifiAsyncChannel) mReplyChannel).setWifiLog(log);
    }

    private class DeathHandlerData {
        DeathHandlerData(DeathRecipient dr, Messenger m) {
            mDeathRecipient = dr;
            mMessenger = m;
        }

        @Override
        public String toString() {
            return "deathRecipient=" + mDeathRecipient + ", messenger=" + mMessenger;
        }

        DeathRecipient mDeathRecipient;
        Messenger mMessenger;
    }
    private Object mLock = new Object();
    private final Map<IBinder, DeathHandlerData> mDeathDataByBinder = new HashMap<>();

    public WifiP2pServiceImpl(Context context, WifiInjector wifiInjector) {
        mContext = context;
        mWifiInjector = wifiInjector;
        mWifiPermissionsUtil = mWifiInjector.getWifiPermissionsUtil();
        mFrameworkFacade = mWifiInjector.getFrameworkFacade();
        mWifiP2pMetrics = mWifiInjector.getWifiP2pMetrics();

        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_WIFI_P2P, 0, NETWORKTYPE, "");

        mP2pSupported = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_DIRECT);

        mThisDevice.primaryDeviceType = mContext.getResources().getString(
                com.android.internal.R.string.config_wifi_p2p_device_type);

        HandlerThread wifiP2pThread = mWifiInjector.getWifiP2pServiceHandlerThread();
        mClientHandler = new ClientHandler(TAG, wifiP2pThread.getLooper());
        mP2pStateMachine = new P2pStateMachine(TAG, wifiP2pThread.getLooper(), mP2pSupported);
        mP2pStateMachine.start();
    }

    /**
     * Obtains the service interface for Managements services
     */
    public void connectivityServiceReady() {
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNwService = INetworkManagementService.Stub.asInterface(b);
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE,
                "WifiP2pService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE,
                "WifiP2pService");
    }

    private void enforceConnectivityInternalPermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL,
                "WifiP2pService");
    }

    private int checkConnectivityInternalPermission() {
        return mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.CONNECTIVITY_INTERNAL);
    }

    private int checkLocationHardwarePermission() {
        return mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.LOCATION_HARDWARE);
    }

    private void enforceConnectivityInternalOrLocationHardwarePermission() {
        if (checkConnectivityInternalPermission() != PackageManager.PERMISSION_GRANTED
                && checkLocationHardwarePermission() != PackageManager.PERMISSION_GRANTED) {
            enforceConnectivityInternalPermission();
        }
    }

    private void stopIpClient() {
        // Invalidate all previous start requests
        mIpClientStartIndex++;
        if (mIpClient != null) {
            try {
                mIpClient.stop();
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            mIpClient = null;
        }
        mDhcpResults = null;
    }

    private void startIpClient(String ifname, Handler smHandler) {
        stopIpClient();
        mIpClientStartIndex++;
        IpClientUtil.makeIpClient(mContext, ifname, new IpClientCallbacksImpl(
                mIpClientStartIndex, smHandler));
    }

    private class IpClientCallbacksImpl extends IpClientCallbacks {
        private final int mStartIndex;
        private final Handler mHandler;

        private IpClientCallbacksImpl(int startIndex, Handler handler) {
            mStartIndex = startIndex;
            mHandler = handler;
        }

        @Override
        public void onIpClientCreated(IIpClient ipClient) {
            mHandler.post(() -> {
                if (mIpClientStartIndex != mStartIndex) {
                    // This start request is obsolete
                    return;
                }
                mIpClient = ipClient;

                final ProvisioningConfiguration config =
                        new ProvisioningConfiguration.Builder()
                                .withoutIpReachabilityMonitor()
                                .withPreDhcpAction(30 * 1000)
                                .withProvisioningTimeoutMs(36 * 1000)
                                .build();
                try {
                    mIpClient.startProvisioning(config.toStableParcelable());
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            });
        }

        @Override
        public void onPreDhcpAction() {
            mP2pStateMachine.sendMessage(IPC_PRE_DHCP_ACTION);
        }
        @Override
        public void onPostDhcpAction() {
            mP2pStateMachine.sendMessage(IPC_POST_DHCP_ACTION);
        }
        @Override
        public void onNewDhcpResults(DhcpResults dhcpResults) {
            mP2pStateMachine.sendMessage(IPC_DHCP_RESULTS, dhcpResults);
        }
        @Override
        public void onProvisioningSuccess(LinkProperties newLp) {
            mP2pStateMachine.sendMessage(IPC_PROVISIONING_SUCCESS);
        }
        @Override
        public void onProvisioningFailure(LinkProperties newLp) {
            mP2pStateMachine.sendMessage(IPC_PROVISIONING_FAILURE);
        }
    }

    /**
     * Get a reference to handler. This is used by a client to establish
     * an AsyncChannel communication with WifiP2pService
     */
    @Override
    public Messenger getMessenger(final IBinder binder) {
        enforceAccessPermission();
        enforceChangePermission();

        synchronized (mLock) {
            final Messenger messenger = new Messenger(mClientHandler);
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "getMessenger: uid=" + getCallingUid() + ", binder=" + binder
                        + ", messenger=" + messenger);
            }

            IBinder.DeathRecipient dr = () -> {
                if (mVerboseLoggingEnabled) Log.d(TAG, "binderDied: binder=" + binder);
                close(binder);
            };

            try {
                binder.linkToDeath(dr, 0);
                mDeathDataByBinder.put(binder, new DeathHandlerData(dr, messenger));
            } catch (RemoteException e) {
                Log.e(TAG, "Error on linkToDeath: e=" + e);
                // fall-through here - won't clean up
            }
            mP2pStateMachine.sendMessage(ENABLE_P2P);

            return messenger;
        }
    }

    /**
     * Get a reference to handler. This is used by a ClientModeImpl to establish
     * an AsyncChannel communication with P2pStateMachine
     * @hide
     */
    @Override
    public Messenger getP2pStateMachineMessenger() {
        enforceConnectivityInternalOrLocationHardwarePermission();
        enforceAccessPermission();
        enforceChangePermission();
        return new Messenger(mP2pStateMachine.getHandler());
    }

    /**
     * Clean-up the state and configuration requested by the closing app. Takes same action as
     * when the app dies (binder death).
     */
    @Override
    public void close(IBinder binder) {
        enforceAccessPermission();
        enforceChangePermission();

        DeathHandlerData dhd;
        synchronized (mLock) {
            dhd = mDeathDataByBinder.get(binder);
            if (dhd == null) {
                Log.w(TAG, "close(): no death recipient for binder");
                return;
            }

            mP2pStateMachine.sendMessage(REMOVE_CLIENT_INFO, 0, 0, binder);
            binder.unlinkToDeath(dhd.mDeathRecipient, 0);
            mDeathDataByBinder.remove(binder);

            // clean-up if there are no more clients registered
            // TODO: what does the ClientModeImpl client do? It isn't tracked through here!
            if (dhd.mMessenger != null && mDeathDataByBinder.isEmpty()) {
                try {
                    dhd.mMessenger.send(
                            mClientHandler.obtainMessage(WifiP2pManager.STOP_DISCOVERY));
                    dhd.mMessenger.send(mClientHandler.obtainMessage(WifiP2pManager.REMOVE_GROUP));
                } catch (RemoteException e) {
                    Log.e(TAG, "close: Failed sending clean-up commands: e=" + e);
                }
                mP2pStateMachine.sendMessage(DISABLE_P2P);
            }
        }
    }

    /** This is used to provide information to drivers to optimize performance depending
     * on the current mode of operation.
     * 0 - disabled
     * 1 - source operation
     * 2 - sink operation
     *
     * As an example, the driver could reduce the channel dwell time during scanning
     * when acting as a source or sink to minimize impact on miracast.
     * @param int mode of operation
     */
    @Override
    public void setMiracastMode(int mode) {
        enforceConnectivityInternalPermission();
        checkConfigureWifiDisplayPermission();
        mP2pStateMachine.sendMessage(SET_MIRACAST_MODE, mode);
    }

    @Override
    public void checkConfigureWifiDisplayPermission() {
        if (!getWfdPermission(Binder.getCallingUid())) {
            throw new SecurityException("Wifi Display Permission denied for uid = "
                    + Binder.getCallingUid());
        }
    }

    private boolean getWfdPermission(int uid) {
        WifiPermissionsWrapper wifiPermissionsWrapper = mWifiInjector.getWifiPermissionsWrapper();
        return wifiPermissionsWrapper.getUidPermission(
                android.Manifest.permission.CONFIGURE_WIFI_DISPLAY, uid)
                != PackageManager.PERMISSION_DENIED;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump WifiP2pService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        mP2pStateMachine.dump(fd, pw, args);
        pw.println("mAutonomousGroup " + mAutonomousGroup);
        pw.println("mJoinExistingGroup " + mJoinExistingGroup);
        pw.println("mDiscoveryStarted " + mDiscoveryStarted);
        pw.println("mNetworkInfo " + mNetworkInfo);
        pw.println("mTemporarilyDisconnectedWifi " + mTemporarilyDisconnectedWifi);
        pw.println("mServiceDiscReqId " + mServiceDiscReqId);
        pw.println("mDeathDataByBinder " + mDeathDataByBinder);
        pw.println("mClientInfoList " + mClientInfoList.size());
        pw.println();

        final IIpClient ipClient = mIpClient;
        if (ipClient != null) {
            pw.println("mIpClient:");
            IpClientUtil.dumpIpClient(ipClient, fd, pw, args);
        }
    }

    /**
     * Handles interaction with ClientModeImpl
     */
    private class P2pStateMachine extends StateMachine {

        private DefaultState mDefaultState = new DefaultState();
        private P2pNotSupportedState mP2pNotSupportedState = new P2pNotSupportedState();
        private P2pDisablingState mP2pDisablingState = new P2pDisablingState();
        private P2pDisabledState mP2pDisabledState = new P2pDisabledState();
        private P2pEnabledState mP2pEnabledState = new P2pEnabledState();
        // Inactive is when p2p is enabled with no connectivity
        private InactiveState mInactiveState = new InactiveState();
        private GroupCreatingState mGroupCreatingState = new GroupCreatingState();
        private UserAuthorizingInviteRequestState mUserAuthorizingInviteRequestState =
                new UserAuthorizingInviteRequestState();
        private UserAuthorizingNegotiationRequestState mUserAuthorizingNegotiationRequestState =
                new UserAuthorizingNegotiationRequestState();
        private ProvisionDiscoveryState mProvisionDiscoveryState = new ProvisionDiscoveryState();
        private GroupNegotiationState mGroupNegotiationState = new GroupNegotiationState();
        private FrequencyConflictState mFrequencyConflictState = new FrequencyConflictState();

        private GroupCreatedState mGroupCreatedState = new GroupCreatedState();
        private UserAuthorizingJoinState mUserAuthorizingJoinState = new UserAuthorizingJoinState();
        private OngoingGroupRemovalState mOngoingGroupRemovalState = new OngoingGroupRemovalState();

        private WifiP2pNative mWifiNative = mWifiInjector.getWifiP2pNative();
        private WifiP2pMonitor mWifiMonitor = mWifiInjector.getWifiP2pMonitor();
        private final WifiP2pDeviceList mPeers = new WifiP2pDeviceList();
        private String mInterfaceName;

        // During a connection, supplicant can tell us that a device was lost. From a supplicant's
        // perspective, the discovery stops during connection and it purges device since it does
        // not get latest updates about the device without being in discovery state.
        // From the framework perspective, the device is still there since we are connecting or
        // connected to it. so we keep these devices in a separate list, so that they are removed
        // when connection is cancelled or lost
        private final WifiP2pDeviceList mPeersLostDuringConnection = new WifiP2pDeviceList();
        private final WifiP2pGroupList mGroups = new WifiP2pGroupList(null,
                new GroupDeleteListener() {
                    @Override
                    public void onDeleteGroup(int netId) {
                        if (mVerboseLoggingEnabled) logd("called onDeleteGroup() netId=" + netId);
                        mWifiNative.removeP2pNetwork(netId);
                        mWifiNative.saveConfig();
                        sendP2pPersistentGroupsChangedBroadcast();
                    }
                });
        private final WifiP2pInfo mWifiP2pInfo = new WifiP2pInfo();
        private WifiP2pGroup mGroup;
        // Is the HAL (HIDL) interface available for use.
        private boolean mIsHalInterfaceAvailable = false;
        // Is wifi on or off.
        private boolean mIsWifiEnabled = false;

        // Saved WifiP2pConfig for an ongoing peer connection. This will never be null.
        // The deviceAddress will be an empty string when the device is inactive
        // or if it is connected without any ongoing join request
        private WifiP2pConfig mSavedPeerConfig = new WifiP2pConfig();

        P2pStateMachine(String name, Looper looper, boolean p2pSupported) {
            super(name, looper);

            // CHECKSTYLE:OFF IndentationCheck
            addState(mDefaultState);
                addState(mP2pNotSupportedState, mDefaultState);
                addState(mP2pDisablingState, mDefaultState);
                addState(mP2pDisabledState, mDefaultState);
                addState(mP2pEnabledState, mDefaultState);
                    addState(mInactiveState, mP2pEnabledState);
                    addState(mGroupCreatingState, mP2pEnabledState);
                        addState(mUserAuthorizingInviteRequestState, mGroupCreatingState);
                        addState(mUserAuthorizingNegotiationRequestState, mGroupCreatingState);
                        addState(mProvisionDiscoveryState, mGroupCreatingState);
                        addState(mGroupNegotiationState, mGroupCreatingState);
                        addState(mFrequencyConflictState, mGroupCreatingState);
                    addState(mGroupCreatedState, mP2pEnabledState);
                        addState(mUserAuthorizingJoinState, mGroupCreatedState);
                        addState(mOngoingGroupRemovalState, mGroupCreatedState);
            // CHECKSTYLE:ON IndentationCheck

            if (p2pSupported) {
                setInitialState(mP2pDisabledState);
            } else {
                setInitialState(mP2pNotSupportedState);
            }
            setLogRecSize(50);
            setLogOnlyTransitions(true);

            if (p2pSupported) {
                // Register for wifi on/off broadcasts
                mContext.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        int wifistate = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                                WifiManager.WIFI_STATE_UNKNOWN);
                        if (wifistate == WifiManager.WIFI_STATE_ENABLED) {
                            mIsWifiEnabled = true;
                            checkAndReEnableP2p();
                        } else {
                            mIsWifiEnabled = false;
                            // Teardown P2P if it's up already.
                            sendMessage(DISABLE_P2P);
                        }
                        checkAndSendP2pStateChangedBroadcast();
                    }
                }, new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION));
                // Register for location mode on/off broadcasts
                mContext.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        /* if location mode is off, ongoing discovery should be stopped.
                         * possible ongoing discovery:
                         * - peer discovery
                         * - service discovery
                         * - group joining scan in native service
                         */
                        if (!mWifiPermissionsUtil.isLocationModeEnabled()) {
                            sendMessage(WifiP2pManager.STOP_DISCOVERY);
                        }
                    }
                }, new IntentFilter(LocationManager.MODE_CHANGED_ACTION));
                // Register for interface availability from HalDeviceManager
                mWifiNative.registerInterfaceAvailableListener((boolean isAvailable) -> {
                    mIsHalInterfaceAvailable = isAvailable;
                    if (isAvailable) {
                        checkAndReEnableP2p();
                    }
                    checkAndSendP2pStateChangedBroadcast();
                }, getHandler());

                mFrameworkFacade.registerContentObserver(mContext,
                        Settings.Global.getUriFor(Settings.Global.WIFI_VERBOSE_LOGGING_ENABLED),
                        true, new ContentObserver(new Handler(looper)) {
                            @Override
                            public void onChange(boolean selfChange) {
                                enableVerboseLogging(mFrameworkFacade.getIntegerSetting(mContext,
                                        Settings.Global.WIFI_VERBOSE_LOGGING_ENABLED, 0));
                            }
                        });
            }
        }

        /**
         * Enable verbose logging for all sub modules.
         */
        private void enableVerboseLogging(int verbose) {
            mVerboseLoggingEnabled = verbose > 0;
            mWifiNative.enableVerboseLogging(verbose);
            mWifiMonitor.enableVerboseLogging(verbose);
        }

        public void registerForWifiMonitorEvents() {
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.AP_STA_CONNECTED_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.AP_STA_DISCONNECTED_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_DEVICE_FOUND_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_DEVICE_LOST_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_FIND_STOPPED_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_GO_NEGOTIATION_FAILURE_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_GO_NEGOTIATION_REQUEST_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_GO_NEGOTIATION_SUCCESS_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_GROUP_FORMATION_FAILURE_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_GROUP_FORMATION_SUCCESS_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_GROUP_REMOVED_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_GROUP_STARTED_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_INVITATION_RECEIVED_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_INVITATION_RESULT_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_PROV_DISC_FAILURE_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_PROV_DISC_PBC_REQ_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_PROV_DISC_PBC_RSP_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.P2P_SERV_DISC_RESP_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.SUP_CONNECTION_EVENT, getHandler());
            mWifiMonitor.registerHandler(mInterfaceName,
                    WifiP2pMonitor.SUP_DISCONNECTION_EVENT, getHandler());

            mWifiMonitor.startMonitoring(mInterfaceName);
        }

        class DefaultState extends State {
            @Override
            public boolean processMessage(Message message) {
                if (mVerboseLoggingEnabled) logd(getName() + message.toString());
                switch (message.what) {
                    case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                        if (message.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                            if (mVerboseLoggingEnabled) {
                                logd("Full connection with ClientModeImpl established");
                            }
                            mWifiChannel = (AsyncChannel) message.obj;
                        } else {
                            loge("Full connection failure, error = " + message.arg1);
                            mWifiChannel = null;
                            transitionTo(mP2pDisabledState);
                        }
                        break;
                    case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                        if (message.arg1 == AsyncChannel.STATUS_SEND_UNSUCCESSFUL) {
                            loge("Send failed, client connection lost");
                        } else {
                            loge("Client connection lost with reason: " + message.arg1);
                        }
                        mWifiChannel = null;
                        transitionTo(mP2pDisabledState);
                        break;
                    case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION:
                        AsyncChannel ac = new WifiAsyncChannel(TAG);
                        ac.connect(mContext, getHandler(), message.replyTo);
                        break;
                    case BLOCK_DISCOVERY:
                        mDiscoveryBlocked = (message.arg1 == ENABLED ? true : false);
                        // always reset this - we went to a state that doesn't support discovery so
                        // it would have stopped regardless
                        mDiscoveryPostponed = false;
                        if (mDiscoveryBlocked) {
                            if (message.obj == null) {
                                Log.e(TAG, "Illegal argument(s)");
                                break;
                            }
                            StateMachine m = (StateMachine) message.obj;
                            try {
                                m.sendMessage(message.arg2);
                            } catch (Exception e) {
                                loge("unable to send BLOCK_DISCOVERY response: " + e);
                            }
                        }
                        break;
                    case WifiP2pManager.DISCOVER_PEERS:
                        replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.STOP_DISCOVERY:
                        replyToMessage(message, WifiP2pManager.STOP_DISCOVERY_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.DISCOVER_SERVICES:
                        replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.CONNECT:
                        replyToMessage(message, WifiP2pManager.CONNECT_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.CANCEL_CONNECT:
                        replyToMessage(message, WifiP2pManager.CANCEL_CONNECT_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.CREATE_GROUP:
                        replyToMessage(message, WifiP2pManager.CREATE_GROUP_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.REMOVE_GROUP:
                        replyToMessage(message, WifiP2pManager.REMOVE_GROUP_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.ADD_LOCAL_SERVICE:
                        replyToMessage(message, WifiP2pManager.ADD_LOCAL_SERVICE_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.REMOVE_LOCAL_SERVICE:
                        replyToMessage(message, WifiP2pManager.REMOVE_LOCAL_SERVICE_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.CLEAR_LOCAL_SERVICES:
                        replyToMessage(message, WifiP2pManager.CLEAR_LOCAL_SERVICES_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.ADD_SERVICE_REQUEST:
                        replyToMessage(message, WifiP2pManager.ADD_SERVICE_REQUEST_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.REMOVE_SERVICE_REQUEST:
                        replyToMessage(message,
                                WifiP2pManager.REMOVE_SERVICE_REQUEST_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.CLEAR_SERVICE_REQUESTS:
                        replyToMessage(message,
                                WifiP2pManager.CLEAR_SERVICE_REQUESTS_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.SET_DEVICE_NAME:
                        replyToMessage(message, WifiP2pManager.SET_DEVICE_NAME_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.DELETE_PERSISTENT_GROUP:
                        replyToMessage(message, WifiP2pManager.DELETE_PERSISTENT_GROUP_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.SET_WFD_INFO:
                        if (!getWfdPermission(message.sendingUid)) {
                            replyToMessage(message, WifiP2pManager.SET_WFD_INFO_FAILED,
                                    WifiP2pManager.ERROR);
                        } else {
                            replyToMessage(message, WifiP2pManager.SET_WFD_INFO_FAILED,
                                    WifiP2pManager.BUSY);
                        }
                        break;
                    case WifiP2pManager.REQUEST_PEERS:
                        replyToMessage(message, WifiP2pManager.RESPONSE_PEERS,
                                getPeers(getCallingPkgName(message.sendingUid, message.replyTo),
                                message.sendingUid));
                        break;
                    case WifiP2pManager.REQUEST_CONNECTION_INFO:
                        replyToMessage(message, WifiP2pManager.RESPONSE_CONNECTION_INFO,
                                new WifiP2pInfo(mWifiP2pInfo));
                        break;
                    case WifiP2pManager.REQUEST_GROUP_INFO:
                        if (!mWifiPermissionsUtil.checkCanAccessWifiDirect(
                                getCallingPkgName(message.sendingUid, message.replyTo),
                                message.sendingUid, false)) {
                            replyToMessage(message, WifiP2pManager.RESPONSE_GROUP_INFO, null);
                            // remain at this state.
                            break;
                        }
                        replyToMessage(message, WifiP2pManager.RESPONSE_GROUP_INFO,
                                maybeEraseOwnDeviceAddress(mGroup, message.sendingUid));
                        break;
                    case WifiP2pManager.REQUEST_PERSISTENT_GROUP_INFO:
                        replyToMessage(message, WifiP2pManager.RESPONSE_PERSISTENT_GROUP_INFO,
                                new WifiP2pGroupList(
                                        maybeEraseOwnDeviceAddress(mGroups, message.sendingUid),
                                        null));
                        break;
                    case WifiP2pManager.REQUEST_P2P_STATE:
                        replyToMessage(message, WifiP2pManager.RESPONSE_P2P_STATE,
                                (mIsWifiEnabled && isHalInterfaceAvailable())
                                ? WifiP2pManager.WIFI_P2P_STATE_ENABLED
                                : WifiP2pManager.WIFI_P2P_STATE_DISABLED);
                        break;
                    case WifiP2pManager.REQUEST_DISCOVERY_STATE:
                        replyToMessage(message, WifiP2pManager.RESPONSE_DISCOVERY_STATE,
                                mDiscoveryStarted
                                ? WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                                : WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED);
                        break;
                    case WifiP2pManager.REQUEST_NETWORK_INFO:
                        replyToMessage(message, WifiP2pManager.RESPONSE_NETWORK_INFO,
                                mNetworkInfo);
                        break;
                    case WifiP2pManager.START_WPS:
                        replyToMessage(message, WifiP2pManager.START_WPS_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.GET_HANDOVER_REQUEST:
                    case WifiP2pManager.GET_HANDOVER_SELECT:
                        replyToMessage(message, WifiP2pManager.RESPONSE_GET_HANDOVER_MESSAGE, null);
                        break;
                    case WifiP2pManager.INITIATOR_REPORT_NFC_HANDOVER:
                    case WifiP2pManager.RESPONDER_REPORT_NFC_HANDOVER:
                        replyToMessage(message, WifiP2pManager.REPORT_NFC_HANDOVER_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pMonitor.P2P_INVITATION_RESULT_EVENT:
                    case WifiP2pMonitor.SUP_CONNECTION_EVENT:
                    case WifiP2pMonitor.SUP_DISCONNECTION_EVENT:
                    case WifiP2pMonitor.P2P_GROUP_REMOVED_EVENT:
                    case WifiP2pMonitor.P2P_DEVICE_FOUND_EVENT:
                    case WifiP2pMonitor.P2P_DEVICE_LOST_EVENT:
                    case WifiP2pMonitor.P2P_FIND_STOPPED_EVENT:
                    case WifiP2pMonitor.P2P_SERV_DISC_RESP_EVENT:
                    case PEER_CONNECTION_USER_ACCEPT:
                    case PEER_CONNECTION_USER_REJECT:
                    case DISCONNECT_WIFI_RESPONSE:
                    case DROP_WIFI_USER_ACCEPT:
                    case DROP_WIFI_USER_REJECT:
                    case GROUP_CREATING_TIMED_OUT:
                    case DISABLE_P2P_TIMED_OUT:
                    case IPC_PRE_DHCP_ACTION:
                    case IPC_POST_DHCP_ACTION:
                    case IPC_DHCP_RESULTS:
                    case IPC_PROVISIONING_SUCCESS:
                    case IPC_PROVISIONING_FAILURE:
                    case WifiP2pMonitor.P2P_PROV_DISC_FAILURE_EVENT:
                    case SET_MIRACAST_MODE:
                    case WifiP2pManager.START_LISTEN:
                    case WifiP2pManager.STOP_LISTEN:
                    case WifiP2pManager.SET_CHANNEL:
                    case ENABLE_P2P:
                        // Enable is lazy and has no response
                        break;
                    case DISABLE_P2P:
                        // If we end up handling in default, p2p is not enabled
                        break;
                    case WifiP2pMonitor.P2P_GROUP_STARTED_EVENT:
                        // unexpected group created, remove
                        if (message.obj == null) {
                            Log.e(TAG, "Illegal arguments");
                            break;
                        }
                        mGroup = (WifiP2pGroup) message.obj;
                        loge("Unexpected group creation, remove " + mGroup);
                        mWifiNative.p2pGroupRemove(mGroup.getInterface());
                        break;
                    case WifiP2pMonitor.P2P_GROUP_FORMATION_FAILURE_EVENT:
                        // A group formation failure is always followed by
                        // a group removed event. Flushing things at group formation
                        // failure causes supplicant issues. Ignore right now.
                        break;
                    case WifiP2pManager.FACTORY_RESET:
                        if (factoryReset(message.sendingUid)) {
                            replyToMessage(message, WifiP2pManager.FACTORY_RESET_SUCCEEDED);
                        } else {
                            replyToMessage(message, WifiP2pManager.FACTORY_RESET_FAILED,
                                    WifiP2pManager.ERROR);
                        }
                        break;
                    case WifiP2pManager.SET_ONGOING_PEER_CONFIG:
                        if (mWifiPermissionsUtil.checkNetworkStackPermission(message.sendingUid)) {
                            WifiP2pConfig peerConfig = (WifiP2pConfig) message.obj;
                            if (isConfigInvalid(peerConfig)) {
                                loge("Dropping set mSavedPeerConfig requeset" + peerConfig);
                                replyToMessage(message,
                                        WifiP2pManager.SET_ONGOING_PEER_CONFIG_FAILED);
                            } else {
                                logd("setSavedPeerConfig to " + peerConfig);
                                mSavedPeerConfig = peerConfig;
                                replyToMessage(message,
                                        WifiP2pManager.SET_ONGOING_PEER_CONFIG_SUCCEEDED);
                            }
                        } else {
                            loge("Permission violation - no NETWORK_STACK permission,"
                                    + " uid = " + message.sendingUid);
                            replyToMessage(message,
                                    WifiP2pManager.SET_ONGOING_PEER_CONFIG_FAILED);
                        }
                        break;
                    case WifiP2pManager.REQUEST_ONGOING_PEER_CONFIG:
                        if (mWifiPermissionsUtil.checkNetworkStackPermission(message.sendingUid)) {
                            replyToMessage(message,
                                    WifiP2pManager.RESPONSE_ONGOING_PEER_CONFIG, mSavedPeerConfig);
                        } else {
                            loge("Permission violation - no NETWORK_STACK permission,"
                                    + " uid = " + message.sendingUid);
                            replyToMessage(message,
                                    WifiP2pManager.RESPONSE_ONGOING_PEER_CONFIG, null);
                        }
                        break;
                    case WifiP2pManager.UPDATE_CHANNEL_INFO:
                        if (!(message.obj instanceof Bundle)) {
                            break;
                        }
                        Bundle bundle = (Bundle) message.obj;
                        String pkgName = bundle.getString(WifiP2pManager.CALLING_PACKAGE);
                        IBinder binder = bundle.getBinder(WifiP2pManager.CALLING_BINDER);
                        try {
                            mWifiPermissionsUtil.checkPackage(message.sendingUid, pkgName);
                        } catch (SecurityException se) {
                            loge("Unable to update calling package, " + se);
                            break;
                        }
                        if (binder != null && message.replyTo != null) {
                            mClientChannelList.put(binder, message.replyTo);
                            ClientInfo clientInfo = getClientInfo(message.replyTo, true);
                            clientInfo.mPackageName = pkgName;
                        }
                        break;
                    case WifiP2pManager.REQUEST_DEVICE_INFO:
                        if (!mWifiPermissionsUtil.checkCanAccessWifiDirect(
                                getCallingPkgName(message.sendingUid, message.replyTo),
                                message.sendingUid, false)) {
                            replyToMessage(message, WifiP2pManager.RESPONSE_DEVICE_INFO, null);
                            break;
                        }
                        replyToMessage(message, WifiP2pManager.RESPONSE_DEVICE_INFO,
                                maybeEraseOwnDeviceAddress(mThisDevice, message.sendingUid));
                        break;
                    default:
                        loge("Unhandled message " + message);
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class P2pNotSupportedState extends State {
            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case WifiP2pManager.DISCOVER_PEERS:
                        replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.STOP_DISCOVERY:
                        replyToMessage(message, WifiP2pManager.STOP_DISCOVERY_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.DISCOVER_SERVICES:
                        replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.CONNECT:
                        replyToMessage(message, WifiP2pManager.CONNECT_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.CANCEL_CONNECT:
                        replyToMessage(message, WifiP2pManager.CANCEL_CONNECT_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.CREATE_GROUP:
                        replyToMessage(message, WifiP2pManager.CREATE_GROUP_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.REMOVE_GROUP:
                        replyToMessage(message, WifiP2pManager.REMOVE_GROUP_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.ADD_LOCAL_SERVICE:
                        replyToMessage(message, WifiP2pManager.ADD_LOCAL_SERVICE_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.REMOVE_LOCAL_SERVICE:
                        replyToMessage(message, WifiP2pManager.REMOVE_LOCAL_SERVICE_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.CLEAR_LOCAL_SERVICES:
                        replyToMessage(message, WifiP2pManager.CLEAR_LOCAL_SERVICES_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.ADD_SERVICE_REQUEST:
                        replyToMessage(message, WifiP2pManager.ADD_SERVICE_REQUEST_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.REMOVE_SERVICE_REQUEST:
                        replyToMessage(message,
                                WifiP2pManager.REMOVE_SERVICE_REQUEST_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.CLEAR_SERVICE_REQUESTS:
                        replyToMessage(message,
                                WifiP2pManager.CLEAR_SERVICE_REQUESTS_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.SET_DEVICE_NAME:
                        replyToMessage(message, WifiP2pManager.SET_DEVICE_NAME_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.DELETE_PERSISTENT_GROUP:
                        replyToMessage(message, WifiP2pManager.DELETE_PERSISTENT_GROUP_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.SET_WFD_INFO:
                        if (!getWfdPermission(message.sendingUid)) {
                            replyToMessage(message, WifiP2pManager.SET_WFD_INFO_FAILED,
                                    WifiP2pManager.ERROR);
                        } else {
                            replyToMessage(message, WifiP2pManager.SET_WFD_INFO_FAILED,
                                    WifiP2pManager.P2P_UNSUPPORTED);
                        }
                        break;
                    case WifiP2pManager.START_WPS:
                        replyToMessage(message, WifiP2pManager.START_WPS_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.START_LISTEN:
                        replyToMessage(message, WifiP2pManager.START_LISTEN_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.STOP_LISTEN:
                        replyToMessage(message, WifiP2pManager.STOP_LISTEN_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;
                    case WifiP2pManager.FACTORY_RESET:
                        replyToMessage(message, WifiP2pManager.FACTORY_RESET_FAILED,
                                WifiP2pManager.P2P_UNSUPPORTED);
                        break;

                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class P2pDisablingState extends State {
            @Override
            public void enter() {
                if (mVerboseLoggingEnabled) logd(getName());
                sendMessageDelayed(obtainMessage(DISABLE_P2P_TIMED_OUT,
                        ++sDisableP2pTimeoutIndex, 0), DISABLE_P2P_WAIT_TIME_MS);
            }

            @Override
            public boolean processMessage(Message message) {
                if (mVerboseLoggingEnabled) logd(getName() + message.toString());
                switch (message.what) {
                    case WifiP2pMonitor.SUP_DISCONNECTION_EVENT:
                        if (mVerboseLoggingEnabled) logd("p2p socket connection lost");
                        transitionTo(mP2pDisabledState);
                        break;
                    case ENABLE_P2P:
                    case DISABLE_P2P:
                    case REMOVE_CLIENT_INFO:
                        deferMessage(message);
                        break;
                    case DISABLE_P2P_TIMED_OUT:
                        if (sDisableP2pTimeoutIndex == message.arg1) {
                            loge("P2p disable timed out");
                            transitionTo(mP2pDisabledState);
                        }
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class P2pDisabledState extends State {
            @Override
            public void enter() {
                if (mVerboseLoggingEnabled) logd(getName());
            }

            private void setupInterfaceFeatures(String interfaceName) {
                if (mContext.getResources().getBoolean(
                        R.bool.config_wifi_p2p_mac_randomization_supported)) {
                    Log.i(TAG, "Supported feature: P2P MAC randomization");
                    mWifiNative.setMacRandomization(true);
                } else {
                    mWifiNative.setMacRandomization(false);
                }
            }

            @Override
            public boolean processMessage(Message message) {
                if (mVerboseLoggingEnabled) logd(getName() + message.toString());
                switch (message.what) {
                    case ENABLE_P2P:
                        if (!mIsWifiEnabled) {
                            Log.e(TAG, "Ignore P2P enable since wifi is " + mIsWifiEnabled);
                            break;
                        }
                        mInterfaceName = mWifiNative.setupInterface((String ifaceName) -> {
                            sendMessage(DISABLE_P2P);
                        }, getHandler());
                        if (mInterfaceName == null) {
                            Log.e(TAG, "Failed to setup interface for P2P");
                            break;
                        }
                        setupInterfaceFeatures(mInterfaceName);
                        try {
                            mNwService.setInterfaceUp(mInterfaceName);
                        } catch (RemoteException re) {
                            loge("Unable to change interface settings: " + re);
                        } catch (IllegalStateException ie) {
                            loge("Unable to change interface settings: " + ie);
                        }
                        registerForWifiMonitorEvents();
                        transitionTo(mInactiveState);
                        break;
                    case REMOVE_CLIENT_INFO:
                        if (!(message.obj instanceof IBinder)) {
                            loge("Invalid obj when REMOVE_CLIENT_INFO");
                            break;
                        }
                        IBinder b = (IBinder) message.obj;
                        // client service info is clear before enter disable p2p,
                        // just need to remove it from list
                        Messenger m = mClientChannelList.remove(b);
                        ClientInfo clientInfo = mClientInfoList.remove(m);
                        if (clientInfo != null) {
                            logd("Remove client - " + clientInfo.mPackageName);
                        }
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class P2pEnabledState extends State {
            @Override
            public void enter() {
                if (mVerboseLoggingEnabled) logd(getName());
                mNetworkInfo.setIsAvailable(true);

                if (isPendingFactoryReset()) {
                    factoryReset(Process.SYSTEM_UID);
                }

                sendP2pConnectionChangedBroadcast();
                initializeP2pSettings();
            }

            @Override
            public boolean processMessage(Message message) {
                if (mVerboseLoggingEnabled) logd(getName() + message.toString());
                switch (message.what) {
                    case WifiP2pMonitor.SUP_DISCONNECTION_EVENT:
                        loge("Unexpected loss of p2p socket connection");
                        transitionTo(mP2pDisabledState);
                        break;
                    case ENABLE_P2P:
                        // Nothing to do
                        break;
                    case DISABLE_P2P:
                        if (mPeers.clear()) {
                            sendPeersChangedBroadcast();
                        }
                        if (mGroups.clear()) sendP2pPersistentGroupsChangedBroadcast();
                        // clear services list for all clients since interface will teardown soon.
                        clearServicesForAllClients();
                        mWifiMonitor.stopMonitoring(mInterfaceName);
                        mWifiNative.teardownInterface();
                        transitionTo(mP2pDisablingState);
                        break;
                    case REMOVE_CLIENT_INFO:
                        if (!(message.obj instanceof IBinder)) {
                            break;
                        }
                        IBinder b = (IBinder) message.obj;
                        // clear client info and remove it from list
                        clearClientInfo(mClientChannelList.get(b));
                        mClientChannelList.remove(b);
                        break;
                    case WifiP2pManager.SET_DEVICE_NAME:
                    {
                        WifiP2pDevice d = (WifiP2pDevice) message.obj;
                        if (d != null && setAndPersistDeviceName(d.deviceName)) {
                            if (mVerboseLoggingEnabled) logd("set device name " + d.deviceName);
                            replyToMessage(message, WifiP2pManager.SET_DEVICE_NAME_SUCCEEDED);
                        } else {
                            replyToMessage(message, WifiP2pManager.SET_DEVICE_NAME_FAILED,
                                    WifiP2pManager.ERROR);
                        }
                        break;
                    }
                    case WifiP2pManager.SET_WFD_INFO:
                    {
                        WifiP2pWfdInfo d = (WifiP2pWfdInfo) message.obj;
                        if (!getWfdPermission(message.sendingUid)) {
                            replyToMessage(message, WifiP2pManager.SET_WFD_INFO_FAILED,
                                    WifiP2pManager.ERROR);
                        } else if (d != null && setWfdInfo(d)) {
                            replyToMessage(message, WifiP2pManager.SET_WFD_INFO_SUCCEEDED);
                        } else {
                            replyToMessage(message, WifiP2pManager.SET_WFD_INFO_FAILED,
                                    WifiP2pManager.ERROR);
                        }
                        break;
                    }
                    case BLOCK_DISCOVERY:
                        boolean blocked = (message.arg1 == ENABLED ? true : false);
                        if (mDiscoveryBlocked == blocked) break;
                        mDiscoveryBlocked = blocked;
                        if (blocked && mDiscoveryStarted) {
                            mWifiNative.p2pStopFind();
                            mDiscoveryPostponed = true;
                        }
                        if (!blocked && mDiscoveryPostponed) {
                            mDiscoveryPostponed = false;
                            mWifiNative.p2pFind(DISCOVER_TIMEOUT_S);
                        }
                        if (blocked) {
                            if (message.obj == null) {
                                Log.e(TAG, "Illegal argument(s)");
                                break;
                            }
                            StateMachine m = (StateMachine) message.obj;
                            try {
                                m.sendMessage(message.arg2);
                            } catch (Exception e) {
                                loge("unable to send BLOCK_DISCOVERY response: " + e);
                            }
                        }
                        break;
                    case WifiP2pManager.DISCOVER_PEERS:
                        if (!mWifiPermissionsUtil.checkCanAccessWifiDirect(
                                getCallingPkgName(message.sendingUid, message.replyTo),
                                message.sendingUid, true)) {
                            replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                                    WifiP2pManager.ERROR);
                            // remain at this state.
                            break;
                        }
                        if (mDiscoveryBlocked) {
                            replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                                    WifiP2pManager.BUSY);
                            break;
                        }
                        // do not send service discovery request while normal find operation.
                        clearSupplicantServiceRequest();
                        if (mWifiNative.p2pFind(DISCOVER_TIMEOUT_S)) {
                            mWifiP2pMetrics.incrementPeerScans();
                            replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_SUCCEEDED);
                            sendP2pDiscoveryChangedBroadcast(true);
                        } else {
                            replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                                    WifiP2pManager.ERROR);
                        }
                        break;
                    case WifiP2pMonitor.P2P_FIND_STOPPED_EVENT:
                        sendP2pDiscoveryChangedBroadcast(false);
                        break;
                    case WifiP2pManager.STOP_DISCOVERY:
                        if (mWifiNative.p2pStopFind()) {
                            replyToMessage(message, WifiP2pManager.STOP_DISCOVERY_SUCCEEDED);
                        } else {
                            replyToMessage(message, WifiP2pManager.STOP_DISCOVERY_FAILED,
                                    WifiP2pManager.ERROR);
                        }
                        break;
                    case WifiP2pManager.DISCOVER_SERVICES:
                        if (!mWifiPermissionsUtil.checkCanAccessWifiDirect(
                                getCallingPkgName(message.sendingUid, message.replyTo),
                                message.sendingUid, true)) {
                            replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_FAILED,
                                    WifiP2pManager.ERROR);
                            // remain at this state.
                            break;
                        }
                        if (mDiscoveryBlocked) {
                            replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_FAILED,
                                    WifiP2pManager.BUSY);
                            break;
                        }
                        if (mVerboseLoggingEnabled) logd(getName() + " discover services");
                        if (!updateSupplicantServiceRequest()) {
                            replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_FAILED,
                                    WifiP2pManager.NO_SERVICE_REQUESTS);
                            break;
                        }
                        if (mWifiNative.p2pFind(DISCOVER_TIMEOUT_S)) {
                            mWifiP2pMetrics.incrementServiceScans();
                            replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_SUCCEEDED);
                        } else {
                            replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_FAILED,
                                    WifiP2pManager.ERROR);
                        }
                        break;
                    case WifiP2pMonitor.P2P_DEVICE_FOUND_EVENT:
                        if (message.obj == null) {
                            Log.e(TAG, "Illegal argument(s)");
                            break;
                        }
                        WifiP2pDevice device = (WifiP2pDevice) message.obj;
                        if (mThisDevice.deviceAddress.equals(device.deviceAddress)) break;
                        mPeers.updateSupplicantDetails(device);
                        sendPeersChangedBroadcast();
                        break;
                    case WifiP2pMonitor.P2P_DEVICE_LOST_EVENT:
                        if (message.obj == null) {
                            Log.e(TAG, "Illegal argument(s)");
                            break;
                        }
                        device = (WifiP2pDevice) message.obj;
                        // Gets current details for the one removed
                        device = mPeers.remove(device.deviceAddress);
                        if (device != null) {
                            sendPeersChangedBroadcast();
                        }
                        break;
                    case WifiP2pManager.ADD_LOCAL_SERVICE:
                        if (!mWifiPermissionsUtil.checkCanAccessWifiDirect(
                                getCallingPkgName(message.sendingUid, message.replyTo),
                                message.sendingUid, false)) {
                            replyToMessage(message, WifiP2pManager.ADD_LOCAL_SERVICE_FAILED);
                            // remain at this state.
                            break;
                        }
                        if (mVerboseLoggingEnabled) logd(getName() + " add service");
                        WifiP2pServiceInfo servInfo = (WifiP2pServiceInfo) message.obj;
                        if (addLocalService(message.replyTo, servInfo)) {
                            replyToMessage(message, WifiP2pManager.ADD_LOCAL_SERVICE_SUCCEEDED);
                        } else {
                            replyToMessage(message, WifiP2pManager.ADD_LOCAL_SERVICE_FAILED);
                        }
                        break;
                    case WifiP2pManager.REMOVE_LOCAL_SERVICE:
                        if (mVerboseLoggingEnabled) logd(getName() + " remove service");
                        servInfo = (WifiP2pServiceInfo) message.obj;
                        removeLocalService(message.replyTo, servInfo);
                        replyToMessage(message, WifiP2pManager.REMOVE_LOCAL_SERVICE_SUCCEEDED);
                        break;
                    case WifiP2pManager.CLEAR_LOCAL_SERVICES:
                        if (mVerboseLoggingEnabled) logd(getName() + " clear service");
                        clearLocalServices(message.replyTo);
                        replyToMessage(message, WifiP2pManager.CLEAR_LOCAL_SERVICES_SUCCEEDED);
                        break;
                    case WifiP2pManager.ADD_SERVICE_REQUEST:
                        if (mVerboseLoggingEnabled) logd(getName() + " add service request");
                        if (!addServiceRequest(message.replyTo,
                                (WifiP2pServiceRequest) message.obj)) {
                            replyToMessage(message, WifiP2pManager.ADD_SERVICE_REQUEST_FAILED);
                            break;
                        }
                        replyToMessage(message, WifiP2pManager.ADD_SERVICE_REQUEST_SUCCEEDED);
                        break;
                    case WifiP2pManager.REMOVE_SERVICE_REQUEST:
                        if (mVerboseLoggingEnabled) logd(getName() + " remove service request");
                        removeServiceRequest(message.replyTo, (WifiP2pServiceRequest) message.obj);
                        replyToMessage(message, WifiP2pManager.REMOVE_SERVICE_REQUEST_SUCCEEDED);
                        break;
                    case WifiP2pManager.CLEAR_SERVICE_REQUESTS:
                        if (mVerboseLoggingEnabled) logd(getName() + " clear service request");
                        clearServiceRequests(message.replyTo);
                        replyToMessage(message, WifiP2pManager.CLEAR_SERVICE_REQUESTS_SUCCEEDED);
                        break;
                    case WifiP2pMonitor.P2P_SERV_DISC_RESP_EVENT:
                        if (mVerboseLoggingEnabled) logd(getName() + " receive service response");
                        if (message.obj == null) {
                            Log.e(TAG, "Illegal argument(s)");
                            break;
                        }
                        List<WifiP2pServiceResponse> sdRespList =
                                (List<WifiP2pServiceResponse>) message.obj;
                        for (WifiP2pServiceResponse resp : sdRespList) {
                            WifiP2pDevice dev =
                                    mPeers.get(resp.getSrcDevice().deviceAddress);
                            resp.setSrcDevice(dev);
                            sendServiceResponse(resp);
                        }
                        break;
                    case WifiP2pManager.DELETE_PERSISTENT_GROUP:
                        if (mVerboseLoggingEnabled) logd(getName() + " delete persistent group");
                        mGroups.remove(message.arg1);
                        mWifiP2pMetrics.updatePersistentGroup(mGroups);
                        replyToMessage(message, WifiP2pManager.DELETE_PERSISTENT_GROUP_SUCCEEDED);
                        break;
                    case SET_MIRACAST_MODE:
                        mWifiNative.setMiracastMode(message.arg1);
                        break;
                    case WifiP2pManager.START_LISTEN:
                        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(
                                message.sendingUid)) {
                            loge("Permission violation - no NETWORK_SETTING permission,"
                                    + " uid = " + message.sendingUid);
                            replyToMessage(message, WifiP2pManager.START_LISTEN_FAILED);
                            break;
                        }
                        if (mVerboseLoggingEnabled) logd(getName() + " start listen mode");
                        mWifiNative.p2pFlush();
                        if (mWifiNative.p2pExtListen(true, 500, 500)) {
                            replyToMessage(message, WifiP2pManager.START_LISTEN_SUCCEEDED);
                        } else {
                            replyToMessage(message, WifiP2pManager.START_LISTEN_FAILED);
                        }
                        break;
                    case WifiP2pManager.STOP_LISTEN:
                        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(
                                message.sendingUid)) {
                            loge("Permission violation - no NETWORK_SETTING permission,"
                                    + " uid = " + message.sendingUid);
                            replyToMessage(message, WifiP2pManager.STOP_LISTEN_FAILED);
                            break;
                        }
                        if (mVerboseLoggingEnabled) logd(getName() + " stop listen mode");
                        if (mWifiNative.p2pExtListen(false, 0, 0)) {
                            replyToMessage(message, WifiP2pManager.STOP_LISTEN_SUCCEEDED);
                        } else {
                            replyToMessage(message, WifiP2pManager.STOP_LISTEN_FAILED);
                        }
                        mWifiNative.p2pFlush();
                        break;
                    case WifiP2pManager.SET_CHANNEL:
                        Bundle p2pChannels = (Bundle) message.obj;
                        int lc = p2pChannels.getInt("lc", 0);
                        int oc = p2pChannels.getInt("oc", 0);
                        if (mVerboseLoggingEnabled) {
                            logd(getName() + " set listen and operating channel");
                        }
                        if (mWifiNative.p2pSetChannel(lc, oc)) {
                            replyToMessage(message, WifiP2pManager.SET_CHANNEL_SUCCEEDED);
                        } else {
                            replyToMessage(message, WifiP2pManager.SET_CHANNEL_FAILED);
                        }
                        break;
                    case WifiP2pManager.GET_HANDOVER_REQUEST:
                        Bundle requestBundle = new Bundle();
                        requestBundle.putString(WifiP2pManager.EXTRA_HANDOVER_MESSAGE,
                                mWifiNative.getNfcHandoverRequest());
                        replyToMessage(message, WifiP2pManager.RESPONSE_GET_HANDOVER_MESSAGE,
                                requestBundle);
                        break;
                    case WifiP2pManager.GET_HANDOVER_SELECT:
                        Bundle selectBundle = new Bundle();
                        selectBundle.putString(WifiP2pManager.EXTRA_HANDOVER_MESSAGE,
                                mWifiNative.getNfcHandoverSelect());
                        replyToMessage(message, WifiP2pManager.RESPONSE_GET_HANDOVER_MESSAGE,
                                selectBundle);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            @Override
            public void exit() {
                sendP2pDiscoveryChangedBroadcast(false);
                mNetworkInfo.setIsAvailable(false);
            }
        }

        class InactiveState extends State {
            @Override
            public void enter() {
                if (mVerboseLoggingEnabled) logd(getName());
                mSavedPeerConfig.invalidate();
            }

            @Override
            public boolean processMessage(Message message) {
                if (mVerboseLoggingEnabled) logd(getName() + message.toString());
                switch (message.what) {
                    case WifiP2pManager.CONNECT:
                        if (!mWifiPermissionsUtil.checkCanAccessWifiDirect(
                                getCallingPkgName(message.sendingUid, message.replyTo),
                                message.sendingUid, false)) {
                            replyToMessage(message, WifiP2pManager.CONNECT_FAILED);
                            // remain at this state.
                            break;
                        }
                        if (mVerboseLoggingEnabled) logd(getName() + " sending connect");
                        WifiP2pConfig config = (WifiP2pConfig) message.obj;

                        boolean isConnectFailed = false;
                        if (isConfigValidAsGroup(config)) {
                            mAutonomousGroup = false;
                            mWifiNative.p2pStopFind();
                            if (mWifiNative.p2pGroupAdd(config, true)) {
                                mWifiP2pMetrics.startConnectionEvent(
                                        P2pConnectionEvent.CONNECTION_FAST,
                                        config);
                                transitionTo(mGroupNegotiationState);
                            } else {
                                loge("Cannot join a group with config.");
                                isConnectFailed = true;
                                replyToMessage(message, WifiP2pManager.CONNECT_FAILED);
                            }
                        } else {
                            if (isConfigInvalid(config)) {
                                loge("Dropping connect request " + config);
                                isConnectFailed = true;
                                replyToMessage(message, WifiP2pManager.CONNECT_FAILED);
                            } else {
                                mAutonomousGroup = false;
                                mWifiNative.p2pStopFind();
                                if (reinvokePersistentGroup(config)) {
                                    mWifiP2pMetrics.startConnectionEvent(
                                            P2pConnectionEvent.CONNECTION_REINVOKE,
                                            config);
                                    transitionTo(mGroupNegotiationState);
                                } else {
                                    mWifiP2pMetrics.startConnectionEvent(
                                            P2pConnectionEvent.CONNECTION_FRESH,
                                            config);
                                    transitionTo(mProvisionDiscoveryState);
                                }
                            }
                        }

                        if (!isConnectFailed) {
                            mSavedPeerConfig = config;
                            mPeers.updateStatus(mSavedPeerConfig.deviceAddress,
                                    WifiP2pDevice.INVITED);
                            sendPeersChangedBroadcast();
                            replyToMessage(message, WifiP2pManager.CONNECT_SUCCEEDED);
                        }
                        break;
                    case WifiP2pManager.STOP_DISCOVERY:
                        if (mWifiNative.p2pStopFind()) {
                            // When discovery stops in inactive state, flush to clear
                            // state peer data
                            mWifiNative.p2pFlush();
                            mServiceDiscReqId = null;
                            replyToMessage(message, WifiP2pManager.STOP_DISCOVERY_SUCCEEDED);
                        } else {
                            replyToMessage(message, WifiP2pManager.STOP_DISCOVERY_FAILED,
                                    WifiP2pManager.ERROR);
                        }
                        break;
                    case WifiP2pMonitor.P2P_GO_NEGOTIATION_REQUEST_EVENT:
                        config = (WifiP2pConfig) message.obj;
                        if (isConfigInvalid(config)) {
                            loge("Dropping GO neg request " + config);
                            break;
                        }
                        mSavedPeerConfig = config;
                        mAutonomousGroup = false;
                        mJoinExistingGroup = false;
                        mWifiP2pMetrics.startConnectionEvent(
                                P2pConnectionEvent.CONNECTION_FRESH,
                                config);
                        transitionTo(mUserAuthorizingNegotiationRequestState);
                        break;
                    case WifiP2pMonitor.P2P_INVITATION_RECEIVED_EVENT:
                        if (message.obj == null) {
                            Log.e(TAG, "Invalid argument(s)");
                            break;
                        }
                        WifiP2pGroup group = (WifiP2pGroup) message.obj;
                        WifiP2pDevice owner = group.getOwner();
                        if (owner == null) {
                            int id = group.getNetworkId();
                            if (id < 0) {
                                loge("Ignored invitation from null owner");
                                break;
                            }

                            String addr = mGroups.getOwnerAddr(id);
                            if (addr != null) {
                                group.setOwner(new WifiP2pDevice(addr));
                                owner = group.getOwner();
                            } else {
                                loge("Ignored invitation from null owner");
                                break;
                            }
                        }
                        config = new WifiP2pConfig();
                        config.deviceAddress = group.getOwner().deviceAddress;
                        if (isConfigInvalid(config)) {
                            loge("Dropping invitation request " + config);
                            break;
                        }
                        mSavedPeerConfig = config;

                        // Check if we have the owner in peer list and use appropriate
                        // wps method. Default is to use PBC.
                        if (owner != null && ((owner = mPeers.get(owner.deviceAddress)) != null)) {
                            if (owner.wpsPbcSupported()) {
                                mSavedPeerConfig.wps.setup = WpsInfo.PBC;
                            } else if (owner.wpsKeypadSupported()) {
                                mSavedPeerConfig.wps.setup = WpsInfo.KEYPAD;
                            } else if (owner.wpsDisplaySupported()) {
                                mSavedPeerConfig.wps.setup = WpsInfo.DISPLAY;
                            }
                        }

                        mAutonomousGroup = false;
                        mJoinExistingGroup = true;
                        mWifiP2pMetrics.startConnectionEvent(
                                P2pConnectionEvent.CONNECTION_FRESH,
                                config);
                        transitionTo(mUserAuthorizingInviteRequestState);
                        break;
                    case WifiP2pMonitor.P2P_PROV_DISC_PBC_REQ_EVENT:
                    case WifiP2pMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT:
                        // We let the supplicant handle the provision discovery response
                        // and wait instead for the GO_NEGOTIATION_REQUEST_EVENT.
                        // Handling provision discovery and issuing a p2p_connect before
                        // group negotiation comes through causes issues
                        break;
                    case WifiP2pMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT:
                        if (message.obj == null) {
                            Log.e(TAG, "Illegal argument(s)");
                            break;
                        }
                        WifiP2pProvDiscEvent provDisc = (WifiP2pProvDiscEvent) message.obj;
                        WifiP2pDevice device = provDisc.device;
                        if (device == null) {
                            loge("Device entry is null");
                            break;
                        }
                        mSavedPeerConfig = new WifiP2pConfig();
                        mSavedPeerConfig.wps.setup = WpsInfo.KEYPAD;
                        mSavedPeerConfig.deviceAddress = device.deviceAddress;
                        mSavedPeerConfig.wps.pin = provDisc.pin;

                        notifyP2pProvDiscShowPinRequest(provDisc.pin, device.deviceAddress);
                        mPeers.updateStatus(device.deviceAddress, WifiP2pDevice.INVITED);
                        sendPeersChangedBroadcast();
                        transitionTo(mUserAuthorizingNegotiationRequestState);
                        break;
                    case WifiP2pManager.CREATE_GROUP:
                        if (!mWifiPermissionsUtil.checkCanAccessWifiDirect(
                                getCallingPkgName(message.sendingUid, message.replyTo),
                                message.sendingUid, false)) {
                            replyToMessage(message, WifiP2pManager.CREATE_GROUP_FAILED,
                                    WifiP2pManager.ERROR);
                            // remain at this state.
                            break;
                        }
                        mAutonomousGroup = true;
                        int netId = message.arg1;
                        config = (WifiP2pConfig) message.obj;
                        boolean ret = false;
                        if (config != null) {
                            if (isConfigValidAsGroup(config)) {
                                mWifiP2pMetrics.startConnectionEvent(
                                        P2pConnectionEvent.CONNECTION_FAST,
                                        config);
                                ret = mWifiNative.p2pGroupAdd(config, false);
                            } else {
                                ret = false;
                            }
                        } else if (netId == WifiP2pGroup.PERSISTENT_NET_ID) {
                            // check if the go persistent group is present.
                            netId = mGroups.getNetworkId(mThisDevice.deviceAddress);
                            if (netId != -1) {
                                mWifiP2pMetrics.startConnectionEvent(
                                        P2pConnectionEvent.CONNECTION_REINVOKE,
                                        null);
                                ret = mWifiNative.p2pGroupAdd(netId);
                            } else {
                                mWifiP2pMetrics.startConnectionEvent(
                                        P2pConnectionEvent.CONNECTION_LOCAL,
                                        null);
                                ret = mWifiNative.p2pGroupAdd(true);
                            }
                        } else {
                            mWifiP2pMetrics.startConnectionEvent(
                                    P2pConnectionEvent.CONNECTION_LOCAL,
                                    null);
                            ret = mWifiNative.p2pGroupAdd(false);
                        }

                        if (ret) {
                            replyToMessage(message, WifiP2pManager.CREATE_GROUP_SUCCEEDED);
                            transitionTo(mGroupNegotiationState);
                        } else {
                            replyToMessage(message, WifiP2pManager.CREATE_GROUP_FAILED,
                                    WifiP2pManager.ERROR);
                            // remain at this state.
                        }
                        break;
                    case WifiP2pMonitor.P2P_GROUP_STARTED_EVENT:
                        if (message.obj == null) {
                            Log.e(TAG, "Invalid argument(s)");
                            break;
                        }
                        mGroup = (WifiP2pGroup) message.obj;
                        if (mVerboseLoggingEnabled) logd(getName() + " group started");
                        if (mGroup.isGroupOwner()
                                && EMPTY_DEVICE_ADDRESS.equals(mGroup.getOwner().deviceAddress)) {
                            // wpa_supplicant doesn't set own device address to go_dev_addr.
                            mGroup.getOwner().deviceAddress = mThisDevice.deviceAddress;
                        }
                        // We hit this scenario when a persistent group is reinvoked
                        if (mGroup.getNetworkId() == WifiP2pGroup.PERSISTENT_NET_ID) {
                            mAutonomousGroup = false;
                            deferMessage(message);
                            transitionTo(mGroupNegotiationState);
                        } else {
                            loge("Unexpected group creation, remove " + mGroup);
                            mWifiNative.p2pGroupRemove(mGroup.getInterface());
                        }
                        break;
                    case WifiP2pManager.START_LISTEN:
                        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(
                                message.sendingUid)) {
                            loge("Permission violation - no NETWORK_SETTING permission,"
                                    + " uid = " + message.sendingUid);
                            replyToMessage(message, WifiP2pManager.START_LISTEN_FAILED);
                            break;
                        }
                        if (mVerboseLoggingEnabled) logd(getName() + " start listen mode");
                        mWifiNative.p2pFlush();
                        if (mWifiNative.p2pExtListen(true, 500, 500)) {
                            replyToMessage(message, WifiP2pManager.START_LISTEN_SUCCEEDED);
                        } else {
                            replyToMessage(message, WifiP2pManager.START_LISTEN_FAILED);
                        }
                        break;
                    case WifiP2pManager.STOP_LISTEN:
                        if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(
                                message.sendingUid)) {
                            loge("Permission violation - no NETWORK_SETTING permission,"
                                    + " uid = " + message.sendingUid);
                            replyToMessage(message, WifiP2pManager.STOP_LISTEN_FAILED);
                            break;
                        }
                        if (mVerboseLoggingEnabled) logd(getName() + " stop listen mode");
                        if (mWifiNative.p2pExtListen(false, 0, 0)) {
                            replyToMessage(message, WifiP2pManager.STOP_LISTEN_SUCCEEDED);
                        } else {
                            replyToMessage(message, WifiP2pManager.STOP_LISTEN_FAILED);
                        }
                        mWifiNative.p2pFlush();
                        break;
                    case WifiP2pManager.SET_CHANNEL:
                        if (message.obj == null) {
                            Log.e(TAG, "Illegal arguments(s)");
                            break;
                        }
                        Bundle p2pChannels = (Bundle) message.obj;
                        int lc = p2pChannels.getInt("lc", 0);
                        int oc = p2pChannels.getInt("oc", 0);
                        if (mVerboseLoggingEnabled) {
                            logd(getName() + " set listen and operating channel");
                        }
                        if (mWifiNative.p2pSetChannel(lc, oc)) {
                            replyToMessage(message, WifiP2pManager.SET_CHANNEL_SUCCEEDED);
                        } else {
                            replyToMessage(message, WifiP2pManager.SET_CHANNEL_FAILED);
                        }
                        break;
                    case WifiP2pManager.INITIATOR_REPORT_NFC_HANDOVER:
                        String handoverSelect = null;

                        if (message.obj != null) {
                            handoverSelect = ((Bundle) message.obj)
                                    .getString(WifiP2pManager.EXTRA_HANDOVER_MESSAGE);
                        }

                        if (handoverSelect != null
                                && mWifiNative.initiatorReportNfcHandover(handoverSelect)) {
                            replyToMessage(message, WifiP2pManager.REPORT_NFC_HANDOVER_SUCCEEDED);
                            transitionTo(mGroupCreatingState);
                        } else {
                            replyToMessage(message, WifiP2pManager.REPORT_NFC_HANDOVER_FAILED);
                        }
                        break;
                    case WifiP2pManager.RESPONDER_REPORT_NFC_HANDOVER:
                        String handoverRequest = null;

                        if (message.obj != null) {
                            handoverRequest = ((Bundle) message.obj)
                                    .getString(WifiP2pManager.EXTRA_HANDOVER_MESSAGE);
                        }

                        if (handoverRequest != null
                                && mWifiNative.responderReportNfcHandover(handoverRequest)) {
                            replyToMessage(message, WifiP2pManager.REPORT_NFC_HANDOVER_SUCCEEDED);
                            transitionTo(mGroupCreatingState);
                        } else {
                            replyToMessage(message, WifiP2pManager.REPORT_NFC_HANDOVER_FAILED);
                        }
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class GroupCreatingState extends State {
            @Override
            public void enter() {
                if (mVerboseLoggingEnabled) logd(getName());
                sendMessageDelayed(obtainMessage(GROUP_CREATING_TIMED_OUT,
                        ++sGroupCreatingTimeoutIndex, 0), GROUP_CREATING_WAIT_TIME_MS);
            }

            @Override
            public boolean processMessage(Message message) {
                if (mVerboseLoggingEnabled) logd(getName() + message.toString());
                boolean ret = HANDLED;
                switch (message.what) {
                    case GROUP_CREATING_TIMED_OUT:
                        if (sGroupCreatingTimeoutIndex == message.arg1) {
                            if (mVerboseLoggingEnabled) logd("Group negotiation timed out");
                            mWifiP2pMetrics.endConnectionEvent(
                                    P2pConnectionEvent.CLF_TIMEOUT);
                            handleGroupCreationFailure();
                            transitionTo(mInactiveState);
                        }
                        break;
                    case WifiP2pMonitor.P2P_DEVICE_LOST_EVENT:
                        if (message.obj == null) {
                            Log.e(TAG, "Illegal argument(s)");
                            break;
                        }
                        WifiP2pDevice device = (WifiP2pDevice) message.obj;
                        if (!mSavedPeerConfig.deviceAddress.equals(device.deviceAddress)) {
                            if (mVerboseLoggingEnabled) {
                                logd("mSavedPeerConfig " + mSavedPeerConfig.deviceAddress
                                        + "device " + device.deviceAddress);
                            }
                            // Do the regular device lost handling
                            ret = NOT_HANDLED;
                            break;
                        }
                        // Do nothing
                        if (mVerboseLoggingEnabled) logd("Add device to lost list " + device);
                        mPeersLostDuringConnection.updateSupplicantDetails(device);
                        break;
                    case WifiP2pManager.DISCOVER_PEERS:
                        // Discovery will break negotiation
                        replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                                WifiP2pManager.BUSY);
                        break;
                    case WifiP2pManager.CANCEL_CONNECT:
                        // Do a supplicant p2p_cancel which only cancels an ongoing
                        // group negotiation. This will fail for a pending provision
                        // discovery or for a pending user action, but at the framework
                        // level, we always treat cancel as succeeded and enter
                        // an inactive state
                        mWifiNative.p2pCancelConnect();
                        mWifiP2pMetrics.endConnectionEvent(
                                P2pConnectionEvent.CLF_CANCEL);
                        handleGroupCreationFailure();
                        transitionTo(mInactiveState);
                        replyToMessage(message, WifiP2pManager.CANCEL_CONNECT_SUCCEEDED);
                        break;
                    case WifiP2pMonitor.P2P_GO_NEGOTIATION_SUCCESS_EVENT:
                        // We hit this scenario when NFC handover is invoked.
                        mAutonomousGroup = false;
                        transitionTo(mGroupNegotiationState);
                        break;
                    default:
                        ret = NOT_HANDLED;
                }
                return ret;
            }
        }

        class UserAuthorizingNegotiationRequestState extends State {
            @Override
            public void enter() {
                if (mVerboseLoggingEnabled) logd(getName());
                if (mSavedPeerConfig.wps.setup == WpsInfo.PBC
                            || TextUtils.isEmpty(mSavedPeerConfig.wps.pin)) {
                    notifyInvitationReceived();
                }
            }

            @Override
            public boolean processMessage(Message message) {
                if (mVerboseLoggingEnabled) logd(getName() + message.toString());
                boolean ret = HANDLED;
                switch (message.what) {
                    case PEER_CONNECTION_USER_ACCEPT:
                        mWifiNative.p2pStopFind();
                        p2pConnectWithPinDisplay(mSavedPeerConfig);
                        mPeers.updateStatus(mSavedPeerConfig.deviceAddress, WifiP2pDevice.INVITED);
                        sendPeersChangedBroadcast();
                        transitionTo(mGroupNegotiationState);
                        break;
                    case PEER_CONNECTION_USER_REJECT:
                        if (mVerboseLoggingEnabled) {
                            logd("User rejected negotiation " + mSavedPeerConfig);
                        }
                        transitionTo(mInactiveState);
                        break;
                    case PEER_CONNECTION_USER_CONFIRM:
                        mSavedPeerConfig.wps.setup = WpsInfo.DISPLAY;
                        mWifiNative.p2pConnect(mSavedPeerConfig, FORM_GROUP);
                        transitionTo(mGroupNegotiationState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return ret;
            }

            @Override
            public void exit() {
                // TODO: dismiss dialog if not already done
            }
        }

        class UserAuthorizingInviteRequestState extends State {
            @Override
            public void enter() {
                if (mVerboseLoggingEnabled) logd(getName());
                notifyInvitationReceived();
            }

            @Override
            public boolean processMessage(Message message) {
                if (mVerboseLoggingEnabled) logd(getName() + message.toString());
                boolean ret = HANDLED;
                switch (message.what) {
                    case PEER_CONNECTION_USER_ACCEPT:
                        mWifiNative.p2pStopFind();
                        if (!reinvokePersistentGroup(mSavedPeerConfig)) {
                            // Do negotiation when persistence fails
                            p2pConnectWithPinDisplay(mSavedPeerConfig);
                        }
                        mPeers.updateStatus(mSavedPeerConfig.deviceAddress, WifiP2pDevice.INVITED);
                        sendPeersChangedBroadcast();
                        transitionTo(mGroupNegotiationState);
                        break;
                    case PEER_CONNECTION_USER_REJECT:
                        if (mVerboseLoggingEnabled) {
                            logd("User rejected invitation " + mSavedPeerConfig);
                        }
                        transitionTo(mInactiveState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return ret;
            }

            @Override
            public void exit() {
                // TODO: dismiss dialog if not already done
            }
        }

        class ProvisionDiscoveryState extends State {
            @Override
            public void enter() {
                if (mVerboseLoggingEnabled) logd(getName());
                mWifiNative.p2pProvisionDiscovery(mSavedPeerConfig);
            }

            @Override
            public boolean processMessage(Message message) {
                if (mVerboseLoggingEnabled) logd(getName() + message.toString());
                WifiP2pProvDiscEvent provDisc = null;
                WifiP2pDevice device = null;
                switch (message.what) {
                    case WifiP2pMonitor.P2P_PROV_DISC_PBC_RSP_EVENT:
                        if (message.obj == null) {
                            Log.e(TAG, "Invalid argument(s)");
                            break;
                        }
                        provDisc = (WifiP2pProvDiscEvent) message.obj;
                        device = provDisc.device;
                        if (device != null
                                && !device.deviceAddress.equals(mSavedPeerConfig.deviceAddress)) {
                            break;
                        }
                        if (mSavedPeerConfig.wps.setup == WpsInfo.PBC) {
                            if (mVerboseLoggingEnabled) logd("Found a match " + mSavedPeerConfig);
                            p2pConnectWithPinDisplay(mSavedPeerConfig);
                            transitionTo(mGroupNegotiationState);
                        }
                        break;
                    case WifiP2pMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT:
                        if (message.obj == null) {
                            Log.e(TAG, "Illegal argument(s)");
                            break;
                        }
                        provDisc = (WifiP2pProvDiscEvent) message.obj;
                        device = provDisc.device;
                        if (device != null
                                && !device.deviceAddress.equals(mSavedPeerConfig.deviceAddress)) {
                            break;
                        }
                        if (mSavedPeerConfig.wps.setup == WpsInfo.KEYPAD) {
                            if (mVerboseLoggingEnabled) logd("Found a match " + mSavedPeerConfig);
                            // we already have the pin
                            if (!TextUtils.isEmpty(mSavedPeerConfig.wps.pin)) {
                                p2pConnectWithPinDisplay(mSavedPeerConfig);
                                transitionTo(mGroupNegotiationState);
                            } else {
                                mJoinExistingGroup = false;
                                transitionTo(mUserAuthorizingNegotiationRequestState);
                            }
                        }
                        break;
                    case WifiP2pMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT:
                        if (message.obj == null) {
                            Log.e(TAG, "Illegal argument(s)");
                            break;
                        }
                        provDisc = (WifiP2pProvDiscEvent) message.obj;
                        device = provDisc.device;
                        if (device == null) {
                            Log.e(TAG, "Invalid device");
                            break;
                        }
                        if (!device.deviceAddress.equals(mSavedPeerConfig.deviceAddress)) {
                            break;
                        }
                        if (mSavedPeerConfig.wps.setup == WpsInfo.DISPLAY) {
                            if (mVerboseLoggingEnabled) logd("Found a match " + mSavedPeerConfig);
                            mSavedPeerConfig.wps.pin = provDisc.pin;
                            p2pConnectWithPinDisplay(mSavedPeerConfig);
                            notifyInvitationSent(provDisc.pin, device.deviceAddress);
                            transitionTo(mGroupNegotiationState);
                        }
                        break;
                    case WifiP2pMonitor.P2P_PROV_DISC_FAILURE_EVENT:
                        loge("provision discovery failed");
                        mWifiP2pMetrics.endConnectionEvent(
                                P2pConnectionEvent.CLF_PROV_DISC_FAIL);
                        handleGroupCreationFailure();
                        transitionTo(mInactiveState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class GroupNegotiationState extends State {
            @Override
            public void enter() {
                if (mVerboseLoggingEnabled) logd(getName());
            }

            @Override
            public boolean processMessage(Message message) {
                if (mVerboseLoggingEnabled) logd(getName() + message.toString());
                switch (message.what) {
                    // We ignore these right now, since we get a GROUP_STARTED notification
                    // afterwards
                    case WifiP2pMonitor.P2P_GO_NEGOTIATION_SUCCESS_EVENT:
                    case WifiP2pMonitor.P2P_GROUP_FORMATION_SUCCESS_EVENT:
                        if (mVerboseLoggingEnabled) logd(getName() + " go success");
                        break;
                    case WifiP2pMonitor.P2P_GROUP_STARTED_EVENT:
                        if (message.obj == null) {
                            Log.e(TAG, "Illegal argument(s)");
                            break;
                        }
                        mGroup = (WifiP2pGroup) message.obj;
                        if (mVerboseLoggingEnabled) logd(getName() + " group started");
                        if (mGroup.isGroupOwner()
                                && EMPTY_DEVICE_ADDRESS.equals(mGroup.getOwner().deviceAddress)) {
                            // wpa_supplicant doesn't set own device address to go_dev_addr.
                            mGroup.getOwner().deviceAddress = mThisDevice.deviceAddress;
                        }
                        if (mGroup.getNetworkId() == WifiP2pGroup.PERSISTENT_NET_ID) {
                             // update cache information and set network id to mGroup.
                            updatePersistentNetworks(RELOAD);
                            String devAddr = mGroup.getOwner().deviceAddress;
                            mGroup.setNetworkId(mGroups.getNetworkId(devAddr,
                                    mGroup.getNetworkName()));
                        }

                        if (mGroup.isGroupOwner()) {
                            // Setting an idle time out on GO causes issues with certain scenarios
                            // on clients where it can be off-channel for longer and with the power
                            // save modes used.
                            // TODO: Verify multi-channel scenarios and supplicant behavior are
                            // better before adding a time out in future
                            // Set group idle timeout of 10 sec, to avoid GO beaconing incase of any
                            // failure during 4-way Handshake.
                            if (!mAutonomousGroup) {
                                mWifiNative.setP2pGroupIdle(mGroup.getInterface(),
                                        GROUP_IDLE_TIME_S);
                            }
                            startDhcpServer(mGroup.getInterface());
                        } else {
                            mWifiNative.setP2pGroupIdle(mGroup.getInterface(), GROUP_IDLE_TIME_S);
                            startIpClient(mGroup.getInterface(), getHandler());
                            WifiP2pDevice groupOwner = mGroup.getOwner();
                            WifiP2pDevice peer = mPeers.get(groupOwner.deviceAddress);
                            if (peer != null) {
                                // update group owner details with peer details found at discovery
                                groupOwner.updateSupplicantDetails(peer);
                                mPeers.updateStatus(groupOwner.deviceAddress,
                                        WifiP2pDevice.CONNECTED);
                                sendPeersChangedBroadcast();
                            } else {
                                // A supplicant bug can lead to reporting an invalid
                                // group owner address (all zeroes) at times. Avoid a
                                // crash, but continue group creation since it is not
                                // essential.
                                logw("Unknown group owner " + groupOwner);
                            }
                        }
                        transitionTo(mGroupCreatedState);
                        break;
                    case WifiP2pMonitor.P2P_GO_NEGOTIATION_FAILURE_EVENT:
                        P2pStatus status = (P2pStatus) message.obj;
                        if (status == P2pStatus.NO_COMMON_CHANNEL) {
                            transitionTo(mFrequencyConflictState);
                            break;
                        }
                        // continue with group removal handling
                    case WifiP2pMonitor.P2P_GROUP_REMOVED_EVENT:
                        if (mVerboseLoggingEnabled) logd(getName() + " go failure");
                        mWifiP2pMetrics.endConnectionEvent(
                                P2pConnectionEvent.CLF_UNKNOWN);
                        handleGroupCreationFailure();
                        transitionTo(mInactiveState);
                        break;
                    case WifiP2pMonitor.P2P_GROUP_FORMATION_FAILURE_EVENT:
                        // A group formation failure is always followed by
                        // a group removed event. Flushing things at group formation
                        // failure causes supplicant issues. Ignore right now.
                        status = (P2pStatus) message.obj;
                        if (status == P2pStatus.NO_COMMON_CHANNEL) {
                            transitionTo(mFrequencyConflictState);
                            break;
                        }
                        break;
                    case WifiP2pMonitor.P2P_INVITATION_RESULT_EVENT:
                        status = (P2pStatus) message.obj;
                        if (status == P2pStatus.SUCCESS) {
                            // invocation was succeeded.
                            // wait P2P_GROUP_STARTED_EVENT.
                            break;
                        }
                        loge("Invitation result " + status);
                        if (status == P2pStatus.UNKNOWN_P2P_GROUP) {
                            // target device has already removed the credential.
                            // So, remove this credential accordingly.
                            int netId = mSavedPeerConfig.netId;
                            if (netId >= 0) {
                                if (mVerboseLoggingEnabled) {
                                    logd("Remove unknown client from the list");
                                }
                                removeClientFromList(netId, mSavedPeerConfig.deviceAddress, true);
                            }

                            // Reinvocation has failed, try group negotiation
                            mSavedPeerConfig.netId = WifiP2pGroup.PERSISTENT_NET_ID;
                            p2pConnectWithPinDisplay(mSavedPeerConfig);
                        } else if (status == P2pStatus.INFORMATION_IS_CURRENTLY_UNAVAILABLE) {

                            // Devices setting persistent_reconnect to 0 in wpa_supplicant
                            // always defer the invocation request and return
                            // "information is currently unavailable" error.
                            // So, try another way to connect for interoperability.
                            mSavedPeerConfig.netId = WifiP2pGroup.PERSISTENT_NET_ID;
                            p2pConnectWithPinDisplay(mSavedPeerConfig);
                        } else if (status == P2pStatus.NO_COMMON_CHANNEL) {
                            transitionTo(mFrequencyConflictState);
                        } else {
                            mWifiP2pMetrics.endConnectionEvent(
                                    P2pConnectionEvent.CLF_INVITATION_FAIL);
                            handleGroupCreationFailure();
                            transitionTo(mInactiveState);
                        }
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        class FrequencyConflictState extends State {
            private AlertDialog mFrequencyConflictDialog;
            @Override
            public void enter() {
                if (mVerboseLoggingEnabled) logd(getName());
                notifyFrequencyConflict();
            }

            private void notifyFrequencyConflict() {
                logd("Notify frequency conflict");
                Resources r = Resources.getSystem();

                AlertDialog dialog = new AlertDialog.Builder(mContext)
                        .setMessage(r.getString(R.string.wifi_p2p_frequency_conflict_message,
                            getDeviceName(mSavedPeerConfig.deviceAddress)))
                        .setPositiveButton(r.getString(R.string.dlg_ok), new OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                sendMessage(DROP_WIFI_USER_ACCEPT);
                            }
                        })
                        .setNegativeButton(r.getString(R.string.decline), new OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                sendMessage(DROP_WIFI_USER_REJECT);
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface arg0) {
                                sendMessage(DROP_WIFI_USER_REJECT);
                            }
                        })
                        .create();
                dialog.setCanceledOnTouchOutside(false);

                dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                WindowManager.LayoutParams attrs = dialog.getWindow().getAttributes();
                attrs.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
                dialog.getWindow().setAttributes(attrs);
                dialog.show();
                mFrequencyConflictDialog = dialog;
            }

            @Override
            public boolean processMessage(Message message) {
                if (mVerboseLoggingEnabled) logd(getName() + message.toString());
                switch (message.what) {
                    case WifiP2pMonitor.P2P_GO_NEGOTIATION_SUCCESS_EVENT:
                    case WifiP2pMonitor.P2P_GROUP_FORMATION_SUCCESS_EVENT:
                        loge(getName() + "group sucess during freq conflict!");
                        break;
                    case WifiP2pMonitor.P2P_GROUP_STARTED_EVENT:
                        loge(getName() + "group started after freq conflict, handle anyway");
                        deferMessage(message);
                        transitionTo(mGroupNegotiationState);
                        break;
                    case WifiP2pMonitor.P2P_GO_NEGOTIATION_FAILURE_EVENT:
                    case WifiP2pMonitor.P2P_GROUP_REMOVED_EVENT:
                    case WifiP2pMonitor.P2P_GROUP_FORMATION_FAILURE_EVENT:
                        // Ignore failures since we retry again
                        break;
                    case DROP_WIFI_USER_REJECT:
                        // User rejected dropping wifi in favour of p2p
                        mWifiP2pMetrics.endConnectionEvent(
                                P2pConnectionEvent.CLF_USER_REJECT);
                        handleGroupCreationFailure();
                        transitionTo(mInactiveState);
                        break;
                    case DROP_WIFI_USER_ACCEPT:
                        // User accepted dropping wifi in favour of p2p
                        if (mWifiChannel != null) {
                            mWifiChannel.sendMessage(WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST, 1);
                        } else {
                            loge("DROP_WIFI_USER_ACCEPT message received when WifiChannel is null");
                        }
                        mTemporarilyDisconnectedWifi = true;
                        break;
                    case DISCONNECT_WIFI_RESPONSE:
                        // Got a response from ClientModeImpl, retry p2p
                        if (mVerboseLoggingEnabled) {
                            logd(getName() + "Wifi disconnected, retry p2p");
                        }
                        transitionTo(mInactiveState);
                        sendMessage(WifiP2pManager.CONNECT, mSavedPeerConfig);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            public void exit() {
                if (mFrequencyConflictDialog != null) mFrequencyConflictDialog.dismiss();
            }
        }

        class GroupCreatedState extends State {
            @Override
            public void enter() {
                if (mVerboseLoggingEnabled) logd(getName());
                // Once connected, peer config details are invalid
                mSavedPeerConfig.invalidate();
                mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, null);

                updateThisDevice(WifiP2pDevice.CONNECTED);

                // DHCP server has already been started if I am a group owner
                if (mGroup.isGroupOwner()) {
                    setWifiP2pInfoOnGroupFormation(
                            NetworkUtils.numericToInetAddress(SERVER_ADDRESS));
                }

                // In case of a negotiation group, connection changed is sent
                // after a client joins. For autonomous, send now
                if (mAutonomousGroup) {
                    sendP2pConnectionChangedBroadcast();
                }

                mWifiP2pMetrics.endConnectionEvent(
                        P2pConnectionEvent.CLF_NONE);
                mWifiP2pMetrics.startGroupEvent(mGroup);
            }

            @Override
            public boolean processMessage(Message message) {
                if (mVerboseLoggingEnabled) logd(getName() + message.toString());
                WifiP2pDevice device = null;
                String deviceAddress = null;
                switch (message.what) {
                    case WifiP2pMonitor.AP_STA_CONNECTED_EVENT:
                        if (message.obj == null) {
                            Log.e(TAG, "Illegal argument(s)");
                            break;
                        }
                        device = (WifiP2pDevice) message.obj;
                        deviceAddress = device.deviceAddress;
                        // Clear timeout that was set when group was started.
                        mWifiNative.setP2pGroupIdle(mGroup.getInterface(), 0);
                        if (deviceAddress != null) {
                            if (mPeers.get(deviceAddress) != null) {
                                mGroup.addClient(mPeers.get(deviceAddress));
                            } else {
                                mGroup.addClient(deviceAddress);
                            }
                            mPeers.updateStatus(deviceAddress, WifiP2pDevice.CONNECTED);
                            if (mVerboseLoggingEnabled) logd(getName() + " ap sta connected");
                            sendPeersChangedBroadcast();
                            mWifiP2pMetrics.updateGroupEvent(mGroup);
                        } else {
                            loge("Connect on null device address, ignore");
                        }
                        sendP2pConnectionChangedBroadcast();
                        break;
                    case WifiP2pMonitor.AP_STA_DISCONNECTED_EVENT:
                        if (message.obj == null) {
                            Log.e(TAG, "Illegal argument(s)");
                            break;
                        }
                        device = (WifiP2pDevice) message.obj;
                        deviceAddress = device.deviceAddress;
                        if (deviceAddress != null) {
                            mPeers.updateStatus(deviceAddress, WifiP2pDevice.AVAILABLE);
                            if (mGroup.removeClient(deviceAddress)) {
                                if (mVerboseLoggingEnabled) logd("Removed client " + deviceAddress);
                                if (!mAutonomousGroup && mGroup.isClientListEmpty()) {
                                    logd("Client list empty, remove non-persistent p2p group");
                                    mWifiNative.p2pGroupRemove(mGroup.getInterface());
                                    // We end up sending connection changed broadcast
                                    // when this happens at exit()
                                } else {
                                    // Notify when a client disconnects from group
                                    sendP2pConnectionChangedBroadcast();
                                }
                                mWifiP2pMetrics.updateGroupEvent(mGroup);
                            } else {
                                if (mVerboseLoggingEnabled) {
                                    logd("Failed to remove client " + deviceAddress);
                                }
                                for (WifiP2pDevice c : mGroup.getClientList()) {
                                    if (mVerboseLoggingEnabled) logd("client " + c.deviceAddress);
                                }
                            }
                            sendPeersChangedBroadcast();
                            if (mVerboseLoggingEnabled) logd(getName() + " ap sta disconnected");
                        } else {
                            loge("Disconnect on unknown device: " + device);
                        }
                        break;
                    case IPC_PRE_DHCP_ACTION:
                        mWifiNative.setP2pPowerSave(mGroup.getInterface(), false);
                        try {
                            mIpClient.completedPreDhcpAction();
                        } catch (RemoteException e) {
                            e.rethrowFromSystemServer();
                        }
                        break;
                    case IPC_POST_DHCP_ACTION:
                        mWifiNative.setP2pPowerSave(mGroup.getInterface(), true);
                        break;
                    case IPC_DHCP_RESULTS:
                        mDhcpResults = (DhcpResults) message.obj;
                        break;
                    case IPC_PROVISIONING_SUCCESS:
                        if (mVerboseLoggingEnabled) logd("mDhcpResults: " + mDhcpResults);
                        if (mDhcpResults != null) {
                            setWifiP2pInfoOnGroupFormation(mDhcpResults.serverAddress);
                        }
                        sendP2pConnectionChangedBroadcast();
                        try {
                            final String ifname = mGroup.getInterface();
                            if (mDhcpResults != null) {
                                mNwService.addInterfaceToLocalNetwork(
                                        ifname, mDhcpResults.getRoutes(ifname));
                            }
                        } catch (Exception e) {
                            loge("Failed to add iface to local network " + e);
                        }
                        break;
                    case IPC_PROVISIONING_FAILURE:
                        loge("IP provisioning failed");
                        mWifiNative.p2pGroupRemove(mGroup.getInterface());
                        break;
                    case WifiP2pManager.REMOVE_GROUP:
                        if (mVerboseLoggingEnabled) logd(getName() + " remove group");
                        if (mWifiNative.p2pGroupRemove(mGroup.getInterface())) {
                            transitionTo(mOngoingGroupRemovalState);
                            replyToMessage(message, WifiP2pManager.REMOVE_GROUP_SUCCEEDED);
                        } else {
                            handleGroupRemoved();
                            transitionTo(mInactiveState);
                            replyToMessage(message, WifiP2pManager.REMOVE_GROUP_FAILED,
                                    WifiP2pManager.ERROR);
                        }
                        break;
                    case WifiP2pMonitor.P2P_GROUP_REMOVED_EVENT:
                        // We do not listen to NETWORK_DISCONNECTION_EVENT for group removal
                        // handling since supplicant actually tries to reconnect after a temporary
                        // disconnect until group idle time out. Eventually, a group removal event
                        // will come when group has been removed.
                        //
                        // When there are connectivity issues during temporary disconnect,
                        // the application will also just remove the group.
                        //
                        // Treating network disconnection as group removal causes race conditions
                        // since supplicant would still maintain the group at that stage.
                        if (mVerboseLoggingEnabled) logd(getName() + " group removed");
                        handleGroupRemoved();
                        transitionTo(mInactiveState);
                        break;
                    case WifiP2pMonitor.P2P_DEVICE_LOST_EVENT:
                        if (message.obj == null) {
                            Log.e(TAG, "Illegal argument(s)");
                            return NOT_HANDLED;
                        }
                        device = (WifiP2pDevice) message.obj;
                        if (!mGroup.contains(device)) {
                            // do the regular device lost handling
                            return NOT_HANDLED;
                        }
                        // Device loss for a connected device indicates
                        // it is not in discovery any more
                        if (mVerboseLoggingEnabled) logd("Add device to lost list " + device);
                        mPeersLostDuringConnection.updateSupplicantDetails(device);
                        return HANDLED;
                    case DISABLE_P2P:
                        sendMessage(WifiP2pManager.REMOVE_GROUP);
                        deferMessage(message);
                        break;
                        // This allows any client to join the GO during the
                        // WPS window
                    case WifiP2pManager.START_WPS:
                        WpsInfo wps = (WpsInfo) message.obj;
                        if (wps == null) {
                            replyToMessage(message, WifiP2pManager.START_WPS_FAILED);
                            break;
                        }
                        boolean ret = true;
                        if (wps.setup == WpsInfo.PBC) {
                            ret = mWifiNative.startWpsPbc(mGroup.getInterface(), null);
                        } else {
                            if (wps.pin == null) {
                                String pin = mWifiNative.startWpsPinDisplay(
                                        mGroup.getInterface(), null);
                                try {
                                    Integer.parseInt(pin);
                                    notifyInvitationSent(pin, "any");
                                } catch (NumberFormatException ignore) {
                                    ret = false;
                                }
                            } else {
                                ret = mWifiNative.startWpsPinKeypad(mGroup.getInterface(),
                                        wps.pin);
                            }
                        }
                        replyToMessage(message, ret ? WifiP2pManager.START_WPS_SUCCEEDED :
                                WifiP2pManager.START_WPS_FAILED);
                        break;
                    case WifiP2pManager.CONNECT:
                        if (!mWifiPermissionsUtil.checkCanAccessWifiDirect(
                                getCallingPkgName(message.sendingUid, message.replyTo),
                                message.sendingUid, false)) {
                            replyToMessage(message, WifiP2pManager.CONNECT_FAILED);
                            // remain at this state.
                            break;
                        }
                        WifiP2pConfig config = (WifiP2pConfig) message.obj;
                        if (isConfigInvalid(config)) {
                            loge("Dropping connect request " + config);
                            replyToMessage(message, WifiP2pManager.CONNECT_FAILED);
                            break;
                        }
                        logd("Inviting device : " + config.deviceAddress);
                        mSavedPeerConfig = config;
                        if (mWifiNative.p2pInvite(mGroup, config.deviceAddress)) {
                            mPeers.updateStatus(config.deviceAddress, WifiP2pDevice.INVITED);
                            sendPeersChangedBroadcast();
                            replyToMessage(message, WifiP2pManager.CONNECT_SUCCEEDED);
                        } else {
                            replyToMessage(message, WifiP2pManager.CONNECT_FAILED,
                                    WifiP2pManager.ERROR);
                        }
                        // TODO: figure out updating the status to declined
                        // when invitation is rejected
                        break;
                    case WifiP2pMonitor.P2P_INVITATION_RESULT_EVENT:
                        P2pStatus status = (P2pStatus) message.obj;
                        if (status == P2pStatus.SUCCESS) {
                            // invocation was succeeded.
                            break;
                        }
                        loge("Invitation result " + status);
                        if (status == P2pStatus.UNKNOWN_P2P_GROUP) {
                            // target device has already removed the credential.
                            // So, remove this credential accordingly.
                            int netId = mGroup.getNetworkId();
                            if (netId >= 0) {
                                if (mVerboseLoggingEnabled) {
                                    logd("Remove unknown client from the list");
                                }
                                removeClientFromList(netId, mSavedPeerConfig.deviceAddress, false);
                                // try invitation.
                                sendMessage(WifiP2pManager.CONNECT, mSavedPeerConfig);
                            }
                        }
                        break;
                    case WifiP2pMonitor.P2P_PROV_DISC_PBC_REQ_EVENT:
                    case WifiP2pMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT:
                    case WifiP2pMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT:
                        WifiP2pProvDiscEvent provDisc = (WifiP2pProvDiscEvent) message.obj;
                        mSavedPeerConfig = new WifiP2pConfig();
                        if (provDisc != null && provDisc.device != null) {
                            mSavedPeerConfig.deviceAddress = provDisc.device.deviceAddress;
                        }
                        if (message.what == WifiP2pMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT) {
                            mSavedPeerConfig.wps.setup = WpsInfo.KEYPAD;
                        } else if (message.what == WifiP2pMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT) {
                            mSavedPeerConfig.wps.setup = WpsInfo.DISPLAY;
                            mSavedPeerConfig.wps.pin = provDisc.pin;
                        } else {
                            mSavedPeerConfig.wps.setup = WpsInfo.PBC;
                        }

                        // According to section 3.2.3 in SPEC, only GO can handle group join.
                        // Multiple groups is not supported, ignore this discovery for GC.
                        if (mGroup.isGroupOwner()) {
                            transitionTo(mUserAuthorizingJoinState);
                        } else {
                            if (mVerboseLoggingEnabled) logd("Ignore provision discovery for GC");
                        }
                        break;
                    case WifiP2pMonitor.P2P_GROUP_STARTED_EVENT:
                        loge("Duplicate group creation event notice, ignore");
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            public void exit() {
                mWifiP2pMetrics.endGroupEvent();
                updateThisDevice(WifiP2pDevice.AVAILABLE);
                resetWifiP2pInfo();
                mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, null, null);
                sendP2pConnectionChangedBroadcast();
            }
        }

        class UserAuthorizingJoinState extends State {
            @Override
            public void enter() {
                if (mVerboseLoggingEnabled) logd(getName());
                notifyInvitationReceived();
            }

            @Override
            public boolean processMessage(Message message) {
                if (mVerboseLoggingEnabled) logd(getName() + message.toString());
                switch (message.what) {
                    case WifiP2pMonitor.P2P_PROV_DISC_PBC_REQ_EVENT:
                    case WifiP2pMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT:
                    case WifiP2pMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT:
                        // Ignore more client requests
                        break;
                    case PEER_CONNECTION_USER_ACCEPT:
                        // Stop discovery to avoid failure due to channel switch
                        mWifiNative.p2pStopFind();
                        if (mSavedPeerConfig.wps.setup == WpsInfo.PBC) {
                            mWifiNative.startWpsPbc(mGroup.getInterface(), null);
                        } else {
                            mWifiNative.startWpsPinKeypad(mGroup.getInterface(),
                                    mSavedPeerConfig.wps.pin);
                        }
                        transitionTo(mGroupCreatedState);
                        break;
                    case PEER_CONNECTION_USER_REJECT:
                        if (mVerboseLoggingEnabled) logd("User rejected incoming request");
                        transitionTo(mGroupCreatedState);
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }

            @Override
            public void exit() {
                // TODO: dismiss dialog if not already done
            }
        }

        class OngoingGroupRemovalState extends State {
            @Override
            public void enter() {
                if (mVerboseLoggingEnabled) logd(getName());
            }

            @Override
            public boolean processMessage(Message message) {
                if (mVerboseLoggingEnabled) logd(getName() + message.toString());
                switch (message.what) {
                    // Group removal ongoing. Multiple calls
                    // end up removing persisted network. Do nothing.
                    case WifiP2pManager.REMOVE_GROUP:
                        replyToMessage(message, WifiP2pManager.REMOVE_GROUP_SUCCEEDED);
                        break;
                    // Parent state will transition out of this state
                    // when removal is complete
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            super.dump(fd, pw, args);
            pw.println("mWifiP2pInfo " + mWifiP2pInfo);
            pw.println("mGroup " + mGroup);
            pw.println("mSavedPeerConfig " + mSavedPeerConfig);
            pw.println("mGroups" + mGroups);
            pw.println();
        }

        // Check & re-enable P2P if needed.
        // P2P interface will be created if all of the below are true:
        // a) Wifi is enabled.
        // b) HAL (HIDL) interface is available.
        // c) There is atleast 1 client app which invoked initialize().
        private void checkAndReEnableP2p() {
            boolean isHalInterfaceAvailable = isHalInterfaceAvailable();
            Log.d(TAG, "Wifi enabled=" + mIsWifiEnabled + ", P2P Interface availability="
                    + isHalInterfaceAvailable + ", Number of clients="
                    + mDeathDataByBinder.size());
            if (mIsWifiEnabled && isHalInterfaceAvailable
                    && !mDeathDataByBinder.isEmpty()) {
                sendMessage(ENABLE_P2P);
            }
        }

        // Ignore judgement if the device do not support HAL (HIDL) interface
        private boolean isHalInterfaceAvailable() {
            return mWifiNative.isHalInterfaceSupported() ? mIsHalInterfaceAvailable : true;
        }

        private void checkAndSendP2pStateChangedBroadcast() {
            boolean isHalInterfaceAvailable = isHalInterfaceAvailable();
            Log.d(TAG, "Wifi enabled=" + mIsWifiEnabled + ", P2P Interface availability="
                    + isHalInterfaceAvailable);
            sendP2pStateChangedBroadcast(mIsWifiEnabled && isHalInterfaceAvailable);
        }

        private void sendP2pStateChangedBroadcast(boolean enabled) {
            final Intent intent = new Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            if (enabled) {
                intent.putExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED);
            } else {
                intent.putExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED);
            }
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }

        private void sendP2pDiscoveryChangedBroadcast(boolean started) {
            if (mDiscoveryStarted == started) return;
            mDiscoveryStarted = started;

            if (mVerboseLoggingEnabled) logd("discovery change broadcast " + started);

            final Intent intent = new Intent(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, started
                    ? WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED :
                    WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED);
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }

        private void sendThisDeviceChangedBroadcast() {
            final Intent intent = new Intent(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                    eraseOwnDeviceAddress(mThisDevice));
            mContext.sendBroadcastAsUserMultiplePermissions(intent, UserHandle.ALL,
                    RECEIVER_PERMISSIONS_FOR_BROADCAST);
        }

        private void sendPeersChangedBroadcast() {
            final Intent intent = new Intent(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            intent.putExtra(WifiP2pManager.EXTRA_P2P_DEVICE_LIST, new WifiP2pDeviceList(mPeers));
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            mContext.sendBroadcastAsUserMultiplePermissions(intent, UserHandle.ALL,
                    RECEIVER_PERMISSIONS_FOR_BROADCAST);
        }

        private void sendP2pConnectionChangedBroadcast() {
            if (mVerboseLoggingEnabled) logd("sending p2p connection changed broadcast");
            Intent intent = new Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                    | Intent.FLAG_RECEIVER_REPLACE_PENDING);
            intent.putExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, new WifiP2pInfo(mWifiP2pInfo));
            intent.putExtra(WifiP2pManager.EXTRA_NETWORK_INFO, new NetworkInfo(mNetworkInfo));
            intent.putExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP, eraseOwnDeviceAddress(mGroup));
            mContext.sendBroadcastAsUserMultiplePermissions(intent, UserHandle.ALL,
                    RECEIVER_PERMISSIONS_FOR_BROADCAST);
            if (mWifiChannel != null) {
                mWifiChannel.sendMessage(WifiP2pServiceImpl.P2P_CONNECTION_CHANGED,
                        new NetworkInfo(mNetworkInfo));
            } else {
                loge("sendP2pConnectionChangedBroadcast(): WifiChannel is null");
            }
        }

        private void sendP2pPersistentGroupsChangedBroadcast() {
            if (mVerboseLoggingEnabled) logd("sending p2p persistent groups changed broadcast");
            Intent intent = new Intent(WifiP2pManager.WIFI_P2P_PERSISTENT_GROUPS_CHANGED_ACTION);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
        }

        private void startDhcpServer(String intf) {
            InterfaceConfiguration ifcg = null;
            try {
                ifcg = mNwService.getInterfaceConfig(intf);
                ifcg.setLinkAddress(new LinkAddress(NetworkUtils.numericToInetAddress(
                            SERVER_ADDRESS), 24));
                ifcg.setInterfaceUp();
                mNwService.setInterfaceConfig(intf, ifcg);
                // This starts the dnsmasq server
                ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(
                        Context.CONNECTIVITY_SERVICE);
                String[] tetheringDhcpRanges = cm.getTetheredDhcpRanges();
                if (mNwService.isTetheringStarted()) {
                    if (mVerboseLoggingEnabled) logd("Stop existing tethering and restart it");
                    mNwService.stopTethering();
                }
                mNwService.tetherInterface(intf);
                mNwService.startTethering(tetheringDhcpRanges);
            } catch (Exception e) {
                loge("Error configuring interface " + intf + ", :" + e);
                return;
            }

            logd("Started Dhcp server on " + intf);
        }

        private void stopDhcpServer(String intf) {
            try {
                mNwService.untetherInterface(intf);
                for (String temp : mNwService.listTetheredInterfaces()) {
                    logd("List all interfaces " + temp);
                    if (temp.compareTo(intf) != 0) {
                        logd("Found other tethering interfaces, so keep tethering alive");
                        return;
                    }
                }
                mNwService.stopTethering();
            } catch (Exception e) {
                loge("Error stopping Dhcp server" + e);
                return;
            } finally {
                logd("Stopped Dhcp server");
            }
        }

        private void notifyP2pEnableFailure() {
            Resources r = Resources.getSystem();
            AlertDialog dialog = new AlertDialog.Builder(mContext)
                    .setTitle(r.getString(R.string.wifi_p2p_dialog_title))
                    .setMessage(r.getString(R.string.wifi_p2p_failed_message))
                    .setPositiveButton(r.getString(R.string.ok), null)
                    .create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            WindowManager.LayoutParams attrs = dialog.getWindow().getAttributes();
            attrs.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
            dialog.getWindow().setAttributes(attrs);
            dialog.show();
        }

        private void addRowToDialog(ViewGroup group, int stringId, String value) {
            Resources r = Resources.getSystem();
            View row = LayoutInflater.from(mContext).inflate(R.layout.wifi_p2p_dialog_row,
                    group, false);
            ((TextView) row.findViewById(R.id.name)).setText(r.getString(stringId));
            ((TextView) row.findViewById(R.id.value)).setText(value);
            group.addView(row);
        }

        private void notifyInvitationSent(String pin, String peerAddress) {
            Resources r = Resources.getSystem();

            final View textEntryView = LayoutInflater.from(mContext)
                    .inflate(R.layout.wifi_p2p_dialog, null);

            ViewGroup group = (ViewGroup) textEntryView.findViewById(R.id.info);
            addRowToDialog(group, R.string.wifi_p2p_to_message, getDeviceName(peerAddress));
            addRowToDialog(group, R.string.wifi_p2p_show_pin_message, pin);

            AlertDialog dialog = new AlertDialog.Builder(mContext)
                    .setTitle(r.getString(R.string.wifi_p2p_invitation_sent_title))
                    .setView(textEntryView)
                    .setPositiveButton(r.getString(R.string.ok), null)
                    .create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            WindowManager.LayoutParams attrs = dialog.getWindow().getAttributes();
            attrs.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
            dialog.getWindow().setAttributes(attrs);
            dialog.show();
        }

        private void notifyP2pProvDiscShowPinRequest(String pin, String peerAddress) {
            Resources r = Resources.getSystem();
            final View textEntryView = LayoutInflater.from(mContext)
                    .inflate(R.layout.wifi_p2p_dialog, null);

            ViewGroup group = (ViewGroup) textEntryView.findViewById(R.id.info);
            addRowToDialog(group, R.string.wifi_p2p_to_message, getDeviceName(peerAddress));
            addRowToDialog(group, R.string.wifi_p2p_show_pin_message, pin);

            AlertDialog dialog = new AlertDialog.Builder(mContext)
                    .setTitle(r.getString(R.string.wifi_p2p_invitation_sent_title))
                    .setView(textEntryView)
                    .setPositiveButton(r.getString(R.string.accept), new OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                sendMessage(PEER_CONNECTION_USER_CONFIRM);
                            }
                    })
                    .create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            WindowManager.LayoutParams attrs = dialog.getWindow().getAttributes();
            attrs.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
            dialog.getWindow().setAttributes(attrs);
            dialog.show();
        }

        private void notifyInvitationReceived() {
            Resources r = Resources.getSystem();
            final WpsInfo wps = mSavedPeerConfig.wps;
            final View textEntryView = LayoutInflater.from(mContext)
                    .inflate(R.layout.wifi_p2p_dialog, null);

            ViewGroup group = (ViewGroup) textEntryView.findViewById(R.id.info);
            addRowToDialog(group, R.string.wifi_p2p_from_message, getDeviceName(
                    mSavedPeerConfig.deviceAddress));

            final EditText pin = (EditText) textEntryView.findViewById(R.id.wifi_p2p_wps_pin);

            AlertDialog dialog = new AlertDialog.Builder(mContext)
                    .setTitle(r.getString(R.string.wifi_p2p_invitation_to_connect_title))
                    .setView(textEntryView)
                    .setPositiveButton(r.getString(R.string.accept), new OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                if (wps.setup == WpsInfo.KEYPAD) {
                                    mSavedPeerConfig.wps.pin = pin.getText().toString();
                                }
                                if (mVerboseLoggingEnabled) {
                                    logd(getName() + " accept invitation " + mSavedPeerConfig);
                                }
                                sendMessage(PEER_CONNECTION_USER_ACCEPT);
                            }
                        })
                    .setNegativeButton(r.getString(R.string.decline), new OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (mVerboseLoggingEnabled) logd(getName() + " ignore connect");
                                sendMessage(PEER_CONNECTION_USER_REJECT);
                            }
                        })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface arg0) {
                                if (mVerboseLoggingEnabled) logd(getName() + " ignore connect");
                                sendMessage(PEER_CONNECTION_USER_REJECT);
                            }
                        })
                    .create();
            dialog.setCanceledOnTouchOutside(false);

            // make the enter pin area or the display pin area visible
            switch (wps.setup) {
                case WpsInfo.KEYPAD:
                    if (mVerboseLoggingEnabled) logd("Enter pin section visible");
                    textEntryView.findViewById(R.id.enter_pin_section).setVisibility(View.VISIBLE);
                    break;
                case WpsInfo.DISPLAY:
                    if (mVerboseLoggingEnabled) logd("Shown pin section visible");
                    addRowToDialog(group, R.string.wifi_p2p_show_pin_message, wps.pin);
                    break;
                default:
                    break;
            }

            if ((r.getConfiguration().uiMode & Configuration.UI_MODE_TYPE_APPLIANCE)
                    == Configuration.UI_MODE_TYPE_APPLIANCE) {
                // For appliance devices, add a key listener which accepts.
                dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {

                    @Override
                    public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                        // TODO: make the actual key come from a config value.
                        if (keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
                            sendMessage(PEER_CONNECTION_USER_ACCEPT);
                            dialog.dismiss();
                            return true;
                        }
                        return false;
                    }
                });
                // TODO: add timeout for this dialog.
                // TODO: update UI in appliance mode to tell user what to do.
            }

            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
            WindowManager.LayoutParams attrs = dialog.getWindow().getAttributes();
            attrs.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
            dialog.getWindow().setAttributes(attrs);
            dialog.show();
        }

        /**
         * This method unifies the persisent group list, cleans up unused
         * networks and if required, updates corresponding broadcast receivers
         * @param boolean if true, reload the group list from scratch
         *                and send broadcast message with fresh list
         */
        private void updatePersistentNetworks(boolean reload) {
            if (reload) mGroups.clear();

            // Save in all cases, including when reload was requested, but
            // no network has been found.
            if (mWifiNative.p2pListNetworks(mGroups) || reload) {
                for (WifiP2pGroup group : mGroups.getGroupList()) {
                    if (mThisDevice.deviceAddress.equals(group.getOwner().deviceAddress)) {
                        group.setOwner(mThisDevice);
                    }
                }
                mWifiNative.saveConfig();
                mWifiP2pMetrics.updatePersistentGroup(mGroups);
                sendP2pPersistentGroupsChangedBroadcast();
            }
        }

        /**
         * A config is valid if it has a peer address that has already been
         * discovered
         * @param WifiP2pConfig config to be validated
         * @return true if it is invalid, false otherwise
         */
        private boolean isConfigInvalid(WifiP2pConfig config) {
            if (config == null) return true;
            if (TextUtils.isEmpty(config.deviceAddress)) return true;
            if (mPeers.get(config.deviceAddress) == null) return true;
            return false;
        }

        /**
         * A config is valid as a group if it has network name and passphrase.
         * Supplicant can construct a group on the fly for creating a group with specified config
         * or join a group without negotiation and WPS.
         * @param WifiP2pConfig config to be validated
         * @return true if it is valid, false otherwise
         */
        private boolean isConfigValidAsGroup(WifiP2pConfig config) {
            if (config == null) return false;
            if (TextUtils.isEmpty(config.deviceAddress)) return false;
            if (!TextUtils.isEmpty(config.networkName)
                    && !TextUtils.isEmpty(config.passphrase)) {
                return true;
            }

            return false;
        }

        private WifiP2pDevice fetchCurrentDeviceDetails(WifiP2pConfig config) {
            if (config == null) return null;
            // Fetch & update group capability from supplicant on the device
            int gc = mWifiNative.getGroupCapability(config.deviceAddress);
            // TODO: The supplicant does not provide group capability changes as an event.
            // Having it pushed as an event would avoid polling for this information right
            // before a connection
            mPeers.updateGroupCapability(config.deviceAddress, gc);
            return mPeers.get(config.deviceAddress);
        }

        /**
         * Erase the MAC address of our interface if it is present in a given device, to prevent
         * apps from having access to persistent identifiers.
         *
         * @param device a device possibly having the same physical address as the wlan interface.
         * @return a copy of the input, possibly with the device address erased.
         */
        private WifiP2pDevice eraseOwnDeviceAddress(WifiP2pDevice device) {
            if (device == null) {
                return null;
            }
            WifiP2pDevice result = new WifiP2pDevice(device);
            if (device.deviceAddress != null
                    && mThisDevice.deviceAddress != null
                    && device.deviceAddress.length() > 0
                    && mThisDevice.deviceAddress.equals(device.deviceAddress)) {
                result.deviceAddress = ANONYMIZED_DEVICE_ADDRESS;
            }
            return result;
        }

        /**
         * Erase the MAC address of our interface if it is set as the device address for any of the
         * devices in a group.
         *
         * @param group a p2p group containing p2p devices.
         * @return a copy of the input, with any devices corresponding to our wlan interface having
         *      their device address erased.
         */
        private WifiP2pGroup eraseOwnDeviceAddress(WifiP2pGroup group) {
            if (group == null) {
                return null;
            }

            WifiP2pGroup result = new WifiP2pGroup(group);

            // Create copies of the clients so they're not shared with the original object.
            for (WifiP2pDevice originalDevice : group.getClientList()) {
                result.removeClient(originalDevice);
                result.addClient(eraseOwnDeviceAddress(originalDevice));
            }

            WifiP2pDevice groupOwner = group.getOwner();
            result.setOwner(eraseOwnDeviceAddress(groupOwner));

            return result;
        }

        /**
         * Erase the MAC address of our interface if it is present in a given device, to prevent
         * apps from having access to persistent identifiers. If the requesting party holds the
         * {@link Manifest.permission.LOCAL_MAC_ADDRESS} permission, the address is not erased.
         *
         * @param device a device possibly having the same physical address as the wlan interface.
         * @param uid the user id of the app that requested the information.
         * @return a copy of the input, possibly with the device address erased.
         */
        private WifiP2pDevice maybeEraseOwnDeviceAddress(WifiP2pDevice device, int uid) {
            if (device == null) {
                return null;
            }
            if (mWifiPermissionsUtil.checkLocalMacAddressPermission(uid)) {
                // Calling app holds the LOCAL_MAC_ADDRESS permission, and is allowed to see this
                // device's MAC.
                return new WifiP2pDevice(device);
            } else {
                return eraseOwnDeviceAddress(device);
            }
        }

        /**
         * Erase the MAC address of our interface if it is set as the device address for any of the
         * devices in a group. If the requesting party holds the
         * {@link Manifest.permission.LOCAL_MAC_ADDRESS} permission, the address is not erased.
         *
         * @param group a p2p group containing p2p devices.
         * @param uid the user id of the app that requested the information.
         * @return a copy of the input, with any devices corresponding to our wlan interface having
         *      their device address erased. If the requesting app holds the LOCAL_MAC_ADDRESS
         *      permission, this method returns a copy of the input.
         */
        private WifiP2pGroup maybeEraseOwnDeviceAddress(WifiP2pGroup group, int uid) {
            if (group == null) {
                return null;
            }
            if (mWifiPermissionsUtil.checkLocalMacAddressPermission(uid)) {
                // Calling app holds the LOCAL_MAC_ADDRESS permission, and is allowed to see this
                // device's MAC.
                return new WifiP2pGroup(group);
            } else {
                return eraseOwnDeviceAddress(group);
            }
        }

        /**
         * Erase the MAC address of our interface if it is set as the device address for any of the
         * devices in a list of groups. If the requesting party holds the
         * {@link Manifest.permission.LOCAL_MAC_ADDRESS} permission, the address is not erased.
         *
         * @param groupList a list of p2p groups containing p2p devices.
         * @param uid the user id of the app that requested the information.
         * @return a copy of the input, with any devices corresponding to our wlan interface having
         *      their device address erased. If the requesting app holds the LOCAL_MAC_ADDRESS
         *      permission, this method returns a copy of the input.
         */
        private WifiP2pGroupList maybeEraseOwnDeviceAddress(WifiP2pGroupList groupList, int uid) {
            if (groupList == null) {
                return null;
            }
            WifiP2pGroupList result = new WifiP2pGroupList();
            for (WifiP2pGroup group : groupList.getGroupList()) {
                result.add(maybeEraseOwnDeviceAddress(group, uid));
            }
            return result;
        }

        /**
         * Start a p2p group negotiation and display pin if necessary
         * @param config for the peer
         */
        private void p2pConnectWithPinDisplay(WifiP2pConfig config) {
            if (config == null) {
                Log.e(TAG, "Illegal argument(s)");
                return;
            }
            WifiP2pDevice dev = fetchCurrentDeviceDetails(config);
            if (dev == null) {
                Log.e(TAG, "Invalid device");
                return;
            }
            String pin = mWifiNative.p2pConnect(config, dev.isGroupOwner());
            try {
                Integer.parseInt(pin);
                notifyInvitationSent(pin, config.deviceAddress);
            } catch (NumberFormatException ignore) {
                // do nothing if p2pConnect did not return a pin
            }
        }

        /**
         * Reinvoke a persistent group.
         *
         * @param config for the peer
         * @return true on success, false on failure
         */
        private boolean reinvokePersistentGroup(WifiP2pConfig config) {
            if (config == null) {
                Log.e(TAG, "Illegal argument(s)");
                return false;
            }
            WifiP2pDevice dev = fetchCurrentDeviceDetails(config);
            if (dev == null) {
                Log.e(TAG, "Invalid device");
                return false;
            }
            boolean join = dev.isGroupOwner();
            String ssid = mWifiNative.p2pGetSsid(dev.deviceAddress);
            if (mVerboseLoggingEnabled) logd("target ssid is " + ssid + " join:" + join);

            if (join && dev.isGroupLimit()) {
                if (mVerboseLoggingEnabled) logd("target device reaches group limit.");

                // if the target group has reached the limit,
                // try group formation.
                join = false;
            } else if (join) {
                int netId = mGroups.getNetworkId(dev.deviceAddress, ssid);
                if (netId >= 0) {
                    // Skip WPS and start 4way handshake immediately.
                    if (!mWifiNative.p2pGroupAdd(netId)) {
                        return false;
                    }
                    return true;
                }
            }

            if (!join && dev.isDeviceLimit()) {
                loge("target device reaches the device limit.");
                return false;
            }

            if (!join && dev.isInvitationCapable()) {
                int netId = WifiP2pGroup.PERSISTENT_NET_ID;
                if (config.netId >= 0) {
                    if (config.deviceAddress.equals(mGroups.getOwnerAddr(config.netId))) {
                        netId = config.netId;
                    }
                } else {
                    netId = mGroups.getNetworkId(dev.deviceAddress);
                }
                if (netId < 0) {
                    netId = getNetworkIdFromClientList(dev.deviceAddress);
                }
                if (mVerboseLoggingEnabled) {
                    logd("netId related with " + dev.deviceAddress + " = " + netId);
                }
                if (netId >= 0) {
                    // Invoke the persistent group.
                    if (mWifiNative.p2pReinvoke(netId, dev.deviceAddress)) {
                        // Save network id. It'll be used when an invitation
                        // result event is received.
                        config.netId = netId;
                        return true;
                    } else {
                        loge("p2pReinvoke() failed, update networks");
                        updatePersistentNetworks(RELOAD);
                        return false;
                    }
                }
            }
            return false;
        }

        /**
         * Return the network id of the group owner profile which has the p2p client with
         * the specified device address in it's client list.
         * If more than one persistent group of the same address is present in its client
         * lists, return the first one.
         *
         * @param deviceAddress p2p device address.
         * @return the network id. if not found, return -1.
         */
        private int getNetworkIdFromClientList(String deviceAddress) {
            if (deviceAddress == null) return -1;

            Collection<WifiP2pGroup> groups = mGroups.getGroupList();
            for (WifiP2pGroup group : groups) {
                int netId = group.getNetworkId();
                String[] p2pClientList = getClientList(netId);
                if (p2pClientList == null) continue;
                for (String client : p2pClientList) {
                    if (deviceAddress.equalsIgnoreCase(client)) {
                        return netId;
                    }
                }
            }
            return -1;
        }

        /**
         * Return p2p client list associated with the specified network id.
         * @param netId network id.
         * @return p2p client list. if not found, return null.
         */
        private String[] getClientList(int netId) {
            String p2pClients = mWifiNative.getP2pClientList(netId);
            if (p2pClients == null) {
                return null;
            }
            return p2pClients.split(" ");
        }

        /**
         * Remove the specified p2p client from the specified profile.
         * @param netId network id of the profile.
         * @param addr p2p client address to be removed.
         * @param isRemovable if true, remove the specified profile if its client
         *             list becomes empty.
         * @return whether removing the specified p2p client is successful or not.
         */
        private boolean removeClientFromList(int netId, String addr, boolean isRemovable) {
            StringBuilder modifiedClientList =  new StringBuilder();
            String[] currentClientList = getClientList(netId);
            boolean isClientRemoved = false;
            if (currentClientList != null) {
                for (String client : currentClientList) {
                    if (!client.equalsIgnoreCase(addr)) {
                        modifiedClientList.append(" ");
                        modifiedClientList.append(client);
                    } else {
                        isClientRemoved = true;
                    }
                }
            }
            if (modifiedClientList.length() == 0 && isRemovable) {
                // the client list is empty. so remove it.
                if (mVerboseLoggingEnabled) logd("Remove unknown network");
                mGroups.remove(netId);
                mWifiP2pMetrics.updatePersistentGroup(mGroups);
                return true;
            }

            if (!isClientRemoved) {
                // specified p2p client is not found. already removed.
                return false;
            }

            if (mVerboseLoggingEnabled) logd("Modified client list: " + modifiedClientList);
            if (modifiedClientList.length() == 0) {
                modifiedClientList.append("\"\"");
            }
            mWifiNative.setP2pClientList(netId, modifiedClientList.toString());
            mWifiNative.saveConfig();
            return true;
        }

        private void setWifiP2pInfoOnGroupFormation(InetAddress serverInetAddress) {
            mWifiP2pInfo.groupFormed = true;
            mWifiP2pInfo.isGroupOwner = mGroup.isGroupOwner();
            mWifiP2pInfo.groupOwnerAddress = serverInetAddress;
        }

        private void resetWifiP2pInfo() {
            mWifiP2pInfo.groupFormed = false;
            mWifiP2pInfo.isGroupOwner = false;
            mWifiP2pInfo.groupOwnerAddress = null;
        }

        private String getDeviceName(String deviceAddress) {
            WifiP2pDevice d = mPeers.get(deviceAddress);
            if (d != null) {
                return d.deviceName;
            }
            //Treat the address as name if there is no match
            return deviceAddress;
        }

        private String getPersistedDeviceName() {
            String deviceName = mFrameworkFacade.getStringSetting(mContext,
                    Settings.Global.WIFI_P2P_DEVICE_NAME);
            if (deviceName == null) {
                // We use the 4 digits of the ANDROID_ID to have a friendly
                // default that has low likelihood of collision with a peer
                String id = mFrameworkFacade.getSecureStringSetting(mContext,
                        Settings.Secure.ANDROID_ID);
                return "Android_" + id.substring(0, 4);
            }
            return deviceName;
        }

        private boolean setAndPersistDeviceName(String devName) {
            if (devName == null) return false;

            if (!mWifiNative.setDeviceName(devName)) {
                loge("Failed to set device name " + devName);
                return false;
            }

            mThisDevice.deviceName = devName;
            mWifiNative.setP2pSsidPostfix("-" + mThisDevice.deviceName);

            mFrameworkFacade.setStringSetting(mContext,
                    Settings.Global.WIFI_P2P_DEVICE_NAME, devName);
            sendThisDeviceChangedBroadcast();
            return true;
        }

        private boolean setWfdInfo(WifiP2pWfdInfo wfdInfo) {
            boolean success;

            if (!wfdInfo.isWfdEnabled()) {
                success = mWifiNative.setWfdEnable(false);
            } else {
                success =
                    mWifiNative.setWfdEnable(true)
                    && mWifiNative.setWfdDeviceInfo(wfdInfo.getDeviceInfoHex());
            }

            if (!success) {
                loge("Failed to set wfd properties");
                return false;
            }

            mThisDevice.wfdInfo = wfdInfo;
            sendThisDeviceChangedBroadcast();
            return true;
        }

        private void initializeP2pSettings() {
            mThisDevice.deviceName = getPersistedDeviceName();
            mWifiNative.setP2pDeviceName(mThisDevice.deviceName);
            // DIRECT-XY-DEVICENAME (XY is randomly generated)
            mWifiNative.setP2pSsidPostfix("-" + mThisDevice.deviceName);
            mWifiNative.setP2pDeviceType(mThisDevice.primaryDeviceType);
            // Supplicant defaults to using virtual display with display
            // which refers to a remote display. Use physical_display
            mWifiNative.setConfigMethods("virtual_push_button physical_display keypad");

            mThisDevice.deviceAddress = mWifiNative.p2pGetDeviceAddress();
            updateThisDevice(WifiP2pDevice.AVAILABLE);
            if (mVerboseLoggingEnabled) logd("DeviceAddress: " + mThisDevice.deviceAddress);
            mWifiNative.p2pFlush();
            mWifiNative.p2pServiceFlush();
            mServiceTransactionId = 0;
            mServiceDiscReqId = null;

            updatePersistentNetworks(RELOAD);
            enableVerboseLogging(mFrameworkFacade.getIntegerSetting(mContext,
                    Settings.Global.WIFI_VERBOSE_LOGGING_ENABLED, 0));
        }

        private void updateThisDevice(int status) {
            mThisDevice.status = status;
            sendThisDeviceChangedBroadcast();
        }

        private void handleGroupCreationFailure() {
            resetWifiP2pInfo();
            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.FAILED, null, null);
            sendP2pConnectionChangedBroadcast();

            // Remove only the peer we failed to connect to so that other devices discovered
            // that have not timed out still remain in list for connection
            boolean peersChanged = mPeers.remove(mPeersLostDuringConnection);
            if (!TextUtils.isEmpty(mSavedPeerConfig.deviceAddress)
                    && mPeers.remove(mSavedPeerConfig.deviceAddress) != null) {
                peersChanged = true;
            }
            if (peersChanged) {
                sendPeersChangedBroadcast();
            }

            mPeersLostDuringConnection.clear();
            mServiceDiscReqId = null;
            sendMessage(WifiP2pManager.DISCOVER_PEERS);
        }

        private void handleGroupRemoved() {
            if (mGroup.isGroupOwner()) {
                stopDhcpServer(mGroup.getInterface());
            } else {
                if (mVerboseLoggingEnabled) logd("stop IpClient");
                stopIpClient();
                try {
                    mNwService.removeInterfaceFromLocalNetwork(mGroup.getInterface());
                } catch (RemoteException e) {
                    loge("Failed to remove iface from local network " + e);
                }
            }

            try {
                mNwService.clearInterfaceAddresses(mGroup.getInterface());
            } catch (Exception e) {
                loge("Failed to clear addresses " + e);
            }

            // Clear any timeout that was set. This is essential for devices
            // that reuse the main p2p interface for a created group.
            mWifiNative.setP2pGroupIdle(mGroup.getInterface(), 0);

            boolean peersChanged = false;
            // Remove only peers part of the group, so that other devices discovered
            // that have not timed out still remain in list for connection
            for (WifiP2pDevice d : mGroup.getClientList()) {
                if (mPeers.remove(d)) peersChanged = true;
            }
            if (mPeers.remove(mGroup.getOwner())) peersChanged = true;
            if (mPeers.remove(mPeersLostDuringConnection)) peersChanged = true;
            if (peersChanged) {
                sendPeersChangedBroadcast();
            }

            mGroup = null;
            mPeersLostDuringConnection.clear();
            mServiceDiscReqId = null;

            if (mTemporarilyDisconnectedWifi) {
                if (mWifiChannel != null) {
                    mWifiChannel.sendMessage(WifiP2pServiceImpl.DISCONNECT_WIFI_REQUEST, 0);
                } else {
                    loge("handleGroupRemoved(): WifiChannel is null");
                }
                mTemporarilyDisconnectedWifi = false;
            }
        }

        private void replyToMessage(Message msg, int what) {
            // State machine initiated requests can have replyTo set to null
            // indicating there are no recipients, we ignore those reply actions
            if (msg.replyTo == null) return;
            Message dstMsg = obtainMessage(msg);
            dstMsg.what = what;
            mReplyChannel.replyToMessage(msg, dstMsg);
        }

        private void replyToMessage(Message msg, int what, int arg1) {
            if (msg.replyTo == null) return;
            Message dstMsg = obtainMessage(msg);
            dstMsg.what = what;
            dstMsg.arg1 = arg1;
            mReplyChannel.replyToMessage(msg, dstMsg);
        }

        private void replyToMessage(Message msg, int what, Object obj) {
            if (msg.replyTo == null) return;
            Message dstMsg = obtainMessage(msg);
            dstMsg.what = what;
            dstMsg.obj = obj;
            mReplyChannel.replyToMessage(msg, dstMsg);
        }

        private Message obtainMessage(Message srcMsg) {
            // arg2 on the source message has a hash code that needs to
            // be retained in replies see WifiP2pManager for details
            Message msg = Message.obtain();
            msg.arg2 = srcMsg.arg2;
            return msg;
        }

        @Override
        protected void logd(String s) {
            Slog.d(TAG, s);
        }

        @Override
        protected void loge(String s) {
            Slog.e(TAG, s);
        }

        /**
         * Update service discovery request to wpa_supplicant.
         */
        private boolean updateSupplicantServiceRequest() {
            clearSupplicantServiceRequest();

            StringBuffer sb = new StringBuffer();
            for (ClientInfo c: mClientInfoList.values()) {
                int key;
                WifiP2pServiceRequest req;
                for (int i = 0; i < c.mReqList.size(); i++) {
                    req = c.mReqList.valueAt(i);
                    if (req != null) {
                        sb.append(req.getSupplicantQuery());
                    }
                }
            }

            if (sb.length() == 0) {
                return false;
            }

            mServiceDiscReqId = mWifiNative.p2pServDiscReq("00:00:00:00:00:00", sb.toString());
            if (mServiceDiscReqId == null) {
                return false;
            }
            return true;
        }

        /**
         * Clear service discovery request in wpa_supplicant
         */
        private void clearSupplicantServiceRequest() {
            if (mServiceDiscReqId == null) return;

            mWifiNative.p2pServDiscCancelReq(mServiceDiscReqId);
            mServiceDiscReqId = null;
        }

        private boolean addServiceRequest(Messenger m, WifiP2pServiceRequest req) {
            if (m == null || req == null) {
                Log.e(TAG, "Illegal argument(s)");
                return false;
            }
            // TODO: We could track individual service adds separately and avoid
            // having to do update all service requests on every new request
            clearClientDeadChannels();

            ClientInfo clientInfo = getClientInfo(m, false);
            if (clientInfo == null) {
                return false;
            }

            ++mServiceTransactionId;
            //The Wi-Fi p2p spec says transaction id should be non-zero
            if (mServiceTransactionId == 0) ++mServiceTransactionId;
            req.setTransactionId(mServiceTransactionId);
            clientInfo.mReqList.put(mServiceTransactionId, req);

            if (mServiceDiscReqId == null) {
                return true;
            }

            return updateSupplicantServiceRequest();
        }

        private void removeServiceRequest(Messenger m, WifiP2pServiceRequest req) {
            if (m == null || req == null) {
                Log.e(TAG, "Illegal argument(s)");
            }

            ClientInfo clientInfo = getClientInfo(m, false);
            if (clientInfo == null) {
                return;
            }

            // Application does not have transaction id information
            // go through stored requests to remove
            boolean removed = false;
            for (int i = 0; i < clientInfo.mReqList.size(); i++) {
                if (req.equals(clientInfo.mReqList.valueAt(i))) {
                    removed = true;
                    clientInfo.mReqList.removeAt(i);
                    break;
                }
            }

            if (!removed) return;

            if (mServiceDiscReqId == null) {
                return;
            }

            updateSupplicantServiceRequest();
        }

        private void clearServiceRequests(Messenger m) {
            if (m == null) {
                Log.e(TAG, "Illegal argument(s)");
                return;
            }

            ClientInfo clientInfo = getClientInfo(m, false);
            if (clientInfo == null) {
                return;
            }

            if (clientInfo.mReqList.size() == 0) {
                return;
            }

            clientInfo.mReqList.clear();

            if (mServiceDiscReqId == null) {
                return;
            }

            updateSupplicantServiceRequest();
        }

        private boolean addLocalService(Messenger m, WifiP2pServiceInfo servInfo) {
            if (m == null || servInfo == null) {
                Log.e(TAG, "Illegal arguments");
                return false;
            }

            clearClientDeadChannels();

            ClientInfo clientInfo = getClientInfo(m, false);

            if (clientInfo == null) {
                return false;
            }

            if (!clientInfo.mServList.add(servInfo)) {
                return false;
            }

            if (!mWifiNative.p2pServiceAdd(servInfo)) {
                clientInfo.mServList.remove(servInfo);
                return false;
            }

            return true;
        }

        private void removeLocalService(Messenger m, WifiP2pServiceInfo servInfo) {
            if (m == null || servInfo == null) {
                Log.e(TAG, "Illegal arguments");
                return;
            }

            ClientInfo clientInfo = getClientInfo(m, false);
            if (clientInfo == null) {
                return;
            }

            mWifiNative.p2pServiceDel(servInfo);
            clientInfo.mServList.remove(servInfo);
        }

        private void clearLocalServices(Messenger m) {
            if (m == null) {
                Log.e(TAG, "Illegal argument(s)");
                return;
            }

            ClientInfo clientInfo = getClientInfo(m, false);
            if (clientInfo == null) {
                return;
            }

            for (WifiP2pServiceInfo servInfo: clientInfo.mServList) {
                mWifiNative.p2pServiceDel(servInfo);
            }

            clientInfo.mServList.clear();
        }

        private void clearClientInfo(Messenger m) {
            // update wpa_supplicant service info
            clearLocalServices(m);
            clearServiceRequests(m);
            // remove client from client list
            ClientInfo clientInfo = mClientInfoList.remove(m);
            if (clientInfo != null) {
                logd("Client:" + clientInfo.mPackageName + " is removed");
            }
        }

        /**
         * Send the service response to the WifiP2pManager.Channel.
         * @param WifiP2pServiceResponse response to service discovery
         */
        private void sendServiceResponse(WifiP2pServiceResponse resp) {
            if (resp == null) {
                Log.e(TAG, "sendServiceResponse with null response");
                return;
            }
            for (ClientInfo c : mClientInfoList.values()) {
                WifiP2pServiceRequest req = c.mReqList.get(resp.getTransactionId());
                if (req != null) {
                    Message msg = Message.obtain();
                    msg.what = WifiP2pManager.RESPONSE_SERVICE;
                    msg.arg1 = 0;
                    msg.arg2 = 0;
                    msg.obj = resp;
                    if (c.mMessenger == null) {
                        continue;
                    }
                    try {
                        c.mMessenger.send(msg);
                    } catch (RemoteException e) {
                        if (mVerboseLoggingEnabled) logd("detect dead channel");
                        clearClientInfo(c.mMessenger);
                        return;
                    }
                }
            }
        }

        /**
         * We don't get notifications of clients that have gone away.
         * We detect this actively when services are added and throw
         * them away.
         *
         * TODO: This can be done better with full async channels.
         */
        private void clearClientDeadChannels() {
            ArrayList<Messenger> deadClients = new ArrayList<Messenger>();

            for (ClientInfo c : mClientInfoList.values()) {
                Message msg = Message.obtain();
                msg.what = WifiP2pManager.PING;
                msg.arg1 = 0;
                msg.arg2 = 0;
                msg.obj = null;
                if (c.mMessenger == null) {
                    continue;
                }
                try {
                    c.mMessenger.send(msg);
                } catch (RemoteException e) {
                    if (mVerboseLoggingEnabled) logd("detect dead channel");
                    deadClients.add(c.mMessenger);
                }
            }

            for (Messenger m : deadClients) {
                clearClientInfo(m);
            }
        }

        /**
         * Return the specified ClientInfo.
         * @param m Messenger
         * @param createIfNotExist if true and the specified channel info does not exist,
         * create new client info.
         * @return the specified ClientInfo.
         */
        private ClientInfo getClientInfo(Messenger m, boolean createIfNotExist) {
            ClientInfo clientInfo = mClientInfoList.get(m);

            if (clientInfo == null && createIfNotExist) {
                if (mVerboseLoggingEnabled) logd("add a new client");
                clientInfo = new ClientInfo(m);
                mClientInfoList.put(m, clientInfo);
            }

            return clientInfo;
        }

        /**
         * Enforces permissions on the caller who is requesting for P2p Peers
         * @param pkg Bundle containing the calling package string
         * @param uid of the caller
         * @return WifiP2pDeviceList the peer list
         */
        private WifiP2pDeviceList getPeers(String pkgName, int uid) {
            // getPeers() is guaranteed to be invoked after Wifi Service is up
            // This ensures getInstance() will return a non-null object now
            if (mWifiPermissionsUtil.checkCanAccessWifiDirect(pkgName, uid, true)) {
                return new WifiP2pDeviceList(mPeers);
            } else {
                return new WifiP2pDeviceList();
            }
        }

        private void setPendingFactoryReset(boolean pending) {
            mFrameworkFacade.setIntegerSetting(mContext,
                    Settings.Global.WIFI_P2P_PENDING_FACTORY_RESET,
                    pending ? 1 : 0);
        }

        private boolean isPendingFactoryReset() {
            int val = mFrameworkFacade.getIntegerSetting(mContext,
                    Settings.Global.WIFI_P2P_PENDING_FACTORY_RESET,
                    0);
            return (val != 0);
        }

        /**
         * Enforces permissions on the caller who is requesting factory reset.
         * @param pkg Bundle containing the calling package string.
         * @param uid The caller uid.
         */
        private boolean factoryReset(int uid) {
            String pkgName = mContext.getPackageManager().getNameForUid(uid);
            UserManager userManager = mWifiInjector.getUserManager();

            if (!mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) return false;

            if (userManager.hasUserRestriction(UserManager.DISALLOW_NETWORK_RESET)) return false;

            if (userManager.hasUserRestriction(UserManager.DISALLOW_CONFIG_WIFI)) return false;

            Log.i(TAG, "factoryReset uid=" + uid + " pkg=" + pkgName);

            if (mNetworkInfo.isAvailable()) {
                if (mWifiNative.p2pListNetworks(mGroups)) {
                    for (WifiP2pGroup group : mGroups.getGroupList()) {
                        mWifiNative.removeP2pNetwork(group.getNetworkId());
                    }
                }
                // reload will save native config and broadcast changed event.
                updatePersistentNetworks(true);
                setPendingFactoryReset(false);
            } else {
                setPendingFactoryReset(true);
            }
            return true;
        }

        /**
        * Get calling package string from Client HashMap
        *
        * @param uid The uid of the caller package
        * @param replyMessenger AsyncChannel handler in caller
        */
        private String getCallingPkgName(int uid, Messenger replyMessenger) {
            ClientInfo clientInfo = mClientInfoList.get(replyMessenger);
            if (clientInfo != null) {
                return clientInfo.mPackageName;
            }
            if (uid == Process.SYSTEM_UID) return mContext.getOpPackageName();
            return null;

        }

        /**
         * Clear all of p2p local service request/response for all p2p clients
         */
        private void clearServicesForAllClients() {
            for (ClientInfo c : mClientInfoList.values()) {
                clearLocalServices(c.mMessenger);
                clearServiceRequests(c.mMessenger);
            }
        }
    }

    /**
     * Information about a particular client and we track the service discovery requests
     * and the local services registered by the client.
     */
    private class ClientInfo {

        // A reference to WifiP2pManager.Channel handler.
        // The response of this request is notified to WifiP2pManager.Channel handler
        private Messenger mMessenger;
        private String mPackageName;


        // A service discovery request list.
        private SparseArray<WifiP2pServiceRequest> mReqList;

        // A local service information list.
        private List<WifiP2pServiceInfo> mServList;

        private ClientInfo(Messenger m) {
            mMessenger = m;
            mPackageName = null;
            mReqList = new SparseArray();
            mServList = new ArrayList<WifiP2pServiceInfo>();
        }
    }
}
