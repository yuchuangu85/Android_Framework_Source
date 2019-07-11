/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi.aware;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.wifi.V1_0.NanStatusType;
import android.hardware.wifi.V1_2.NanDataPathChannelInfo;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.net.wifi.aware.Characteristics;
import android.net.wifi.aware.ConfigRequest;
import android.net.wifi.aware.IWifiAwareDiscoverySessionCallback;
import android.net.wifi.aware.IWifiAwareEventCallback;
import android.net.wifi.aware.IWifiAwareMacAddressProvider;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareNetworkSpecifier;
import android.os.Bundle;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.MessageUtils;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.WakeupMessage;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.server.wifi.util.WifiPermissionsWrapper;

import libcore.util.HexEncoding;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the state of the Wi-Fi Aware system service.
 */
public class WifiAwareStateManager implements WifiAwareShellCommand.DelegatedShellCommand {
    private static final String TAG = "WifiAwareStateManager";
    private static final boolean VDBG = false; // STOPSHIP if true
    private static final boolean VVDBG = false; // STOPSHIP if true - for detailed state machine
    /* package */ boolean mDbg = false;

    @VisibleForTesting
    public static final String HAL_COMMAND_TIMEOUT_TAG = TAG + " HAL Command Timeout";

    @VisibleForTesting
    public static final String HAL_SEND_MESSAGE_TIMEOUT_TAG = TAG + " HAL Send Message Timeout";

    @VisibleForTesting
    public static final String HAL_DATA_PATH_CONFIRM_TIMEOUT_TAG =
            TAG + " HAL Data Path Confirm Timeout";

    /*
     * State machine message types. There are sub-types for the messages (except for TIMEOUTs).
     * Format:
     * - Message.arg1: contains message sub-type
     * - Message.arg2: contains transaction ID for RESPONSE & RESPONSE_TIMEOUT
     */
    private static final int MESSAGE_TYPE_COMMAND = 1;
    private static final int MESSAGE_TYPE_RESPONSE = 2;
    private static final int MESSAGE_TYPE_NOTIFICATION = 3;
    private static final int MESSAGE_TYPE_RESPONSE_TIMEOUT = 4;
    private static final int MESSAGE_TYPE_SEND_MESSAGE_TIMEOUT = 5;
    private static final int MESSAGE_TYPE_DATA_PATH_TIMEOUT = 6;

    /*
     * Message sub-types:
     */
    private static final int COMMAND_TYPE_CONNECT = 100;
    private static final int COMMAND_TYPE_DISCONNECT = 101;
    private static final int COMMAND_TYPE_TERMINATE_SESSION = 102;
    private static final int COMMAND_TYPE_PUBLISH = 103;
    private static final int COMMAND_TYPE_UPDATE_PUBLISH = 104;
    private static final int COMMAND_TYPE_SUBSCRIBE = 105;
    private static final int COMMAND_TYPE_UPDATE_SUBSCRIBE = 106;
    private static final int COMMAND_TYPE_ENQUEUE_SEND_MESSAGE = 107;
    private static final int COMMAND_TYPE_ENABLE_USAGE = 108;
    private static final int COMMAND_TYPE_DISABLE_USAGE = 109;
    private static final int COMMAND_TYPE_GET_CAPABILITIES = 111;
    private static final int COMMAND_TYPE_CREATE_ALL_DATA_PATH_INTERFACES = 112;
    private static final int COMMAND_TYPE_DELETE_ALL_DATA_PATH_INTERFACES = 113;
    private static final int COMMAND_TYPE_CREATE_DATA_PATH_INTERFACE = 114;
    private static final int COMMAND_TYPE_DELETE_DATA_PATH_INTERFACE = 115;
    private static final int COMMAND_TYPE_INITIATE_DATA_PATH_SETUP = 116;
    private static final int COMMAND_TYPE_RESPOND_TO_DATA_PATH_SETUP_REQUEST = 117;
    private static final int COMMAND_TYPE_END_DATA_PATH = 118;
    private static final int COMMAND_TYPE_TRANSMIT_NEXT_MESSAGE = 119;
    private static final int COMMAND_TYPE_RECONFIGURE = 120;
    private static final int COMMAND_TYPE_DELAYED_INITIALIZATION = 121;
    private static final int COMMAND_TYPE_GET_AWARE = 122;
    private static final int COMMAND_TYPE_RELEASE_AWARE = 123;

    private static final int RESPONSE_TYPE_ON_CONFIG_SUCCESS = 200;
    private static final int RESPONSE_TYPE_ON_CONFIG_FAIL = 201;
    private static final int RESPONSE_TYPE_ON_SESSION_CONFIG_SUCCESS = 202;
    private static final int RESPONSE_TYPE_ON_SESSION_CONFIG_FAIL = 203;
    private static final int RESPONSE_TYPE_ON_MESSAGE_SEND_QUEUED_SUCCESS = 204;
    private static final int RESPONSE_TYPE_ON_MESSAGE_SEND_QUEUED_FAIL = 205;
    private static final int RESPONSE_TYPE_ON_CAPABILITIES_UPDATED = 206;
    private static final int RESPONSE_TYPE_ON_CREATE_INTERFACE = 207;
    private static final int RESPONSE_TYPE_ON_DELETE_INTERFACE = 208;
    private static final int RESPONSE_TYPE_ON_INITIATE_DATA_PATH_SUCCESS = 209;
    private static final int RESPONSE_TYPE_ON_INITIATE_DATA_PATH_FAIL = 210;
    private static final int RESPONSE_TYPE_ON_RESPOND_TO_DATA_PATH_SETUP_REQUEST = 211;
    private static final int RESPONSE_TYPE_ON_END_DATA_PATH = 212;
    private static final int RESPONSE_TYPE_ON_DISABLE = 213;

    private static final int NOTIFICATION_TYPE_INTERFACE_CHANGE = 301;
    private static final int NOTIFICATION_TYPE_CLUSTER_CHANGE = 302;
    private static final int NOTIFICATION_TYPE_MATCH = 303;
    private static final int NOTIFICATION_TYPE_SESSION_TERMINATED = 304;
    private static final int NOTIFICATION_TYPE_MESSAGE_RECEIVED = 305;
    private static final int NOTIFICATION_TYPE_AWARE_DOWN = 306;
    private static final int NOTIFICATION_TYPE_ON_MESSAGE_SEND_SUCCESS = 307;
    private static final int NOTIFICATION_TYPE_ON_MESSAGE_SEND_FAIL = 308;
    private static final int NOTIFICATION_TYPE_ON_DATA_PATH_REQUEST = 309;
    private static final int NOTIFICATION_TYPE_ON_DATA_PATH_CONFIRM = 310;
    private static final int NOTIFICATION_TYPE_ON_DATA_PATH_END = 311;
    private static final int NOTIFICATION_TYPE_ON_DATA_PATH_SCHED_UPDATE = 312;

    private static final SparseArray<String> sSmToString = MessageUtils.findMessageNames(
            new Class[]{WifiAwareStateManager.class},
            new String[]{"MESSAGE_TYPE", "COMMAND_TYPE", "RESPONSE_TYPE", "NOTIFICATION_TYPE"});

    /*
     * Keys used when passing (some) arguments to the Handler thread (too many
     * arguments to pass in the short-cut Message members).
     */
    private static final String MESSAGE_BUNDLE_KEY_SESSION_TYPE = "session_type";
    private static final String MESSAGE_BUNDLE_KEY_SESSION_ID = "session_id";
    private static final String MESSAGE_BUNDLE_KEY_CONFIG = "config";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE = "message";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE_PEER_ID = "message_peer_id";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE_ID = "message_id";
    private static final String MESSAGE_BUNDLE_KEY_SSI_DATA = "ssi_data";
    private static final String MESSAGE_BUNDLE_KEY_FILTER_DATA = "filter_data";
    private static final String MESSAGE_BUNDLE_KEY_MAC_ADDRESS = "mac_address";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE_DATA = "message_data";
    private static final String MESSAGE_BUNDLE_KEY_REQ_INSTANCE_ID = "req_instance_id";
    private static final String MESSAGE_BUNDLE_KEY_SEND_MESSAGE_ENQUEUE_TIME = "message_queue_time";
    private static final String MESSAGE_BUNDLE_KEY_RETRY_COUNT = "retry_count";
    private static final String MESSAGE_BUNDLE_KEY_SUCCESS_FLAG = "success_flag";
    private static final String MESSAGE_BUNDLE_KEY_STATUS_CODE = "status_code";
    private static final String MESSAGE_BUNDLE_KEY_INTERFACE_NAME = "interface_name";
    private static final String MESSAGE_BUNDLE_KEY_CHANNEL_REQ_TYPE = "channel_request_type";
    private static final String MESSAGE_BUNDLE_KEY_CHANNEL = "channel";
    private static final String MESSAGE_BUNDLE_KEY_PEER_ID = "peer_id";
    private static final String MESSAGE_BUNDLE_KEY_UID = "uid";
    private static final String MESSAGE_BUNDLE_KEY_PID = "pid";
    private static final String MESSAGE_BUNDLE_KEY_CALLING_PACKAGE = "calling_package";
    private static final String MESSAGE_BUNDLE_KEY_SENT_MESSAGE = "send_message";
    private static final String MESSAGE_BUNDLE_KEY_MESSAGE_ARRIVAL_SEQ = "message_arrival_seq";
    private static final String MESSAGE_BUNDLE_KEY_NOTIFY_IDENTITY_CHANGE = "notify_identity_chg";
    private static final String MESSAGE_BUNDLE_KEY_PMK = "pmk";
    private static final String MESSAGE_BUNDLE_KEY_PASSPHRASE = "passphrase";
    private static final String MESSAGE_BUNDLE_KEY_OOB = "out_of_band";
    private static final String MESSAGE_RANGING_INDICATION = "ranging_indication";
    private static final String MESSAGE_RANGE_MM = "range_mm";
    private static final String MESSAGE_BUNDLE_KEY_NDP_IDS = "ndp_ids";

    private WifiAwareNativeApi mWifiAwareNativeApi;
    private WifiAwareNativeManager mWifiAwareNativeManager;

    /*
     * Asynchronous access with no lock
     */
    private volatile boolean mUsageEnabled = false;

    /*
     * Synchronous access: state is only accessed through the state machine
     * handler thread: no need to use a lock.
     */
    private Context mContext;
    private WifiAwareMetrics mAwareMetrics;
    private volatile Capabilities mCapabilities;
    private volatile Characteristics mCharacteristics = null;
    private WifiAwareStateMachine mSm;
    public WifiAwareDataPathStateManager mDataPathMgr;
    private PowerManager mPowerManager;
    private LocationManager mLocationManager;
    private WifiManager mWifiManager;

    private final SparseArray<WifiAwareClientState> mClients = new SparseArray<>();
    private ConfigRequest mCurrentAwareConfiguration = null;
    private boolean mCurrentIdentityNotification = false;

    private static final byte[] ALL_ZERO_MAC = new byte[] {0, 0, 0, 0, 0, 0};
    private byte[] mCurrentDiscoveryInterfaceMac = ALL_ZERO_MAC;

    public WifiAwareStateManager() {
        onReset();
    }

    /**
     * Inject references to other manager objects. Needed to resolve
     * circular dependencies and to allow mocking.
     */
    public void setNative(WifiAwareNativeManager wifiAwareNativeManager,
            WifiAwareNativeApi wifiAwareNativeApi) {
        mWifiAwareNativeManager = wifiAwareNativeManager;
        mWifiAwareNativeApi = wifiAwareNativeApi;
    }

    /*
     * parameters settable through shell command
     */
    public static final String PARAM_ON_IDLE_DISABLE_AWARE = "on_idle_disable_aware";
    public static final int PARAM_ON_IDLE_DISABLE_AWARE_DEFAULT = 1; // 0 = false, 1 = true

    private Map<String, Integer> mSettableParameters = new HashMap<>();

    /**
     * Interpreter of adb shell command 'adb shell wifiaware native_api ...'.
     *
     * @return -1 if parameter not recognized or invalid value, 0 otherwise.
     */
    @Override
    public int onCommand(ShellCommand parentShell) {
        final PrintWriter pw_err = parentShell.getErrPrintWriter();
        final PrintWriter pw_out = parentShell.getOutPrintWriter();

        String subCmd = parentShell.getNextArgRequired();
        if (VDBG) Log.v(TAG, "onCommand: subCmd='" + subCmd + "'");
        switch (subCmd) {
            case "set": {
                String name = parentShell.getNextArgRequired();
                if (VDBG) Log.v(TAG, "onCommand: name='" + name + "'");
                if (!mSettableParameters.containsKey(name)) {
                    pw_err.println("Unknown parameter name -- '" + name + "'");
                    return -1;
                }

                String valueStr = parentShell.getNextArgRequired();
                if (VDBG) Log.v(TAG, "onCommand: valueStr='" + valueStr + "'");
                int value;
                try {
                    value = Integer.valueOf(valueStr);
                } catch (NumberFormatException e) {
                    pw_err.println("Can't convert value to integer -- '" + valueStr + "'");
                    return -1;
                }
                mSettableParameters.put(name, value);
                return 0;
            }
            case "get": {
                String name = parentShell.getNextArgRequired();
                if (VDBG) Log.v(TAG, "onCommand: name='" + name + "'");
                if (!mSettableParameters.containsKey(name)) {
                    pw_err.println("Unknown parameter name -- '" + name + "'");
                    return -1;
                }

                pw_out.println((int) mSettableParameters.get(name));
                return 0;
            }
            case "get_capabilities": {
                JSONObject j = new JSONObject();
                if (mCapabilities != null) {
                    try {
                        j.put("maxConcurrentAwareClusters",
                                mCapabilities.maxConcurrentAwareClusters);
                        j.put("maxPublishes", mCapabilities.maxPublishes);
                        j.put("maxSubscribes", mCapabilities.maxSubscribes);
                        j.put("maxServiceNameLen", mCapabilities.maxServiceNameLen);
                        j.put("maxMatchFilterLen", mCapabilities.maxMatchFilterLen);
                        j.put("maxTotalMatchFilterLen", mCapabilities.maxTotalMatchFilterLen);
                        j.put("maxServiceSpecificInfoLen", mCapabilities.maxServiceSpecificInfoLen);
                        j.put("maxExtendedServiceSpecificInfoLen",
                                mCapabilities.maxExtendedServiceSpecificInfoLen);
                        j.put("maxNdiInterfaces", mCapabilities.maxNdiInterfaces);
                        j.put("maxNdpSessions", mCapabilities.maxNdpSessions);
                        j.put("maxAppInfoLen", mCapabilities.maxAppInfoLen);
                        j.put("maxQueuedTransmitMessages", mCapabilities.maxQueuedTransmitMessages);
                        j.put("maxSubscribeInterfaceAddresses",
                                mCapabilities.maxSubscribeInterfaceAddresses);
                        j.put("supportedCipherSuites", mCapabilities.supportedCipherSuites);
                    } catch (JSONException e) {
                        Log.e(TAG, "onCommand: get_capabilities e=" + e);
                    }
                }
                pw_out.println(j.toString());
                return 0;
            }
            case "allow_ndp_any": {
                String flag = parentShell.getNextArgRequired();
                if (VDBG) Log.v(TAG, "onCommand: flag='" + flag + "'");
                if (mDataPathMgr == null) {
                    pw_err.println("Null Aware data-path manager - can't configure");
                    return -1;
                }
                if (TextUtils.equals("true", flag)) {
                    mDataPathMgr.mAllowNdpResponderFromAnyOverride = true;
                } else  if (TextUtils.equals("false", flag)) {
                    mDataPathMgr.mAllowNdpResponderFromAnyOverride = false;
                } else {
                    pw_err.println(
                            "Unknown configuration flag for 'allow_ndp_any' - true|false expected"
                                    + " -- '"
                                    + flag + "'");
                    return -1;
                }
            }
            default:
                pw_err.println("Unknown 'wifiaware state_mgr <cmd>'");
        }

        return -1;
    }

    @Override
    public void onReset() {
        mSettableParameters.put(PARAM_ON_IDLE_DISABLE_AWARE, PARAM_ON_IDLE_DISABLE_AWARE_DEFAULT);
        if (mDataPathMgr != null) {
            mDataPathMgr.mAllowNdpResponderFromAnyOverride = false;
        }
    }

    @Override
    public void onHelp(String command, ShellCommand parentShell) {
        final PrintWriter pw = parentShell.getOutPrintWriter();

        pw.println("  " + command);
        pw.println("    set <name> <value>: sets named parameter to value. Names: "
                + mSettableParameters.keySet());
        pw.println("    get <name>: gets named parameter value. Names: "
                + mSettableParameters.keySet());
        pw.println("    get_capabilities: prints out the capabilities as a JSON string");
        pw.println(
                "    allow_ndp_any true|false: configure whether Responders can be specified to "
                        + "accept requests from ANY requestor (null peer spec)");
    }

    /**
     * Initialize the handler of the state manager with the specified thread
     * looper.
     *
     * @param looper Thread looper on which to run the handler.
     */
    public void start(Context context, Looper looper, WifiAwareMetrics awareMetrics,
            WifiPermissionsUtil wifiPermissionsUtil, WifiPermissionsWrapper permissionsWrapper) {
        Log.i(TAG, "start()");

        mContext = context;
        mAwareMetrics = awareMetrics;
        mSm = new WifiAwareStateMachine(TAG, looper);
        mSm.setDbg(VVDBG);
        mSm.start();

        mDataPathMgr = new WifiAwareDataPathStateManager(this);
        mDataPathMgr.start(mContext, mSm.getHandler().getLooper(), awareMetrics,
                wifiPermissionsUtil, permissionsWrapper);

        mPowerManager = mContext.getSystemService(PowerManager.class);
        mLocationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (VDBG) Log.v(TAG, "BroadcastReceiver: action=" + action);
                if (action.equals(Intent.ACTION_SCREEN_ON)
                        || action.equals(Intent.ACTION_SCREEN_OFF)) {
                    reconfigure();
                }

                if (action.equals(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)) {
                    if (mSettableParameters.get(PARAM_ON_IDLE_DISABLE_AWARE) != 0) {
                        if (mPowerManager.isDeviceIdleMode()) {
                            disableUsage();
                        } else {
                            enableUsage();
                        }
                    } else {
                        reconfigure();
                    }
                }
            }
        }, intentFilter);

        intentFilter = new IntentFilter();
        intentFilter.addAction(LocationManager.MODE_CHANGED_ACTION);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mDbg) Log.v(TAG, "onReceive: MODE_CHANGED_ACTION: intent=" + intent);
                if (mLocationManager.isLocationEnabled()) {
                    enableUsage();
                } else {
                    disableUsage();
                }
            }
        }, intentFilter);

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean isEnabled = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;
                if (isEnabled) {
                    enableUsage();
                } else {
                    disableUsage();
                }
            }
        }, intentFilter);
    }

    /**
     * Initialize the late-initialization sub-services: depend on other services already existing.
     */
    public void startLate() {
        delayedInitialization();
    }

    /**
     * Get the client state for the specified ID (or null if none exists).
     */
    /* package */ WifiAwareClientState getClient(int clientId) {
        return mClients.get(clientId);
    }

    /**
     * Get the capabilities.
     */
    public Capabilities getCapabilities() {
        return mCapabilities;
    }

    /**
     * Get the public characteristics derived from the capabilities. Use lazy initialization.
     */
    public Characteristics getCharacteristics() {
        if (mCharacteristics == null && mCapabilities != null) {
            mCharacteristics = mCapabilities.toPublicCharacteristics();
        }

        return mCharacteristics;
    }

    /*
     * Cross-service API: synchronized but independent of state machine
     */

    /**
     * Translate (and return in the callback) the peerId to its MAC address representation.
     */
    public void requestMacAddresses(int uid, List<Integer> peerIds,
            IWifiAwareMacAddressProvider callback) {
        mSm.getHandler().post(() -> {
            if (VDBG) Log.v(TAG, "requestMacAddresses: uid=" + uid + ", peerIds=" + peerIds);
            Map<Integer, byte[]> peerIdToMacMap = new HashMap<>();
            for (int i = 0; i < mClients.size(); ++i) {
                WifiAwareClientState client = mClients.valueAt(i);
                if (client.getUid() != uid) {
                    continue;
                }

                SparseArray<WifiAwareDiscoverySessionState> sessions = client.getSessions();
                for (int j = 0; j < sessions.size(); ++j) {
                    WifiAwareDiscoverySessionState session = sessions.valueAt(j);

                    for (int peerId : peerIds) {
                        WifiAwareDiscoverySessionState.PeerInfo peerInfo = session.getPeerInfo(
                                peerId);
                        if (peerInfo != null) {
                            peerIdToMacMap.put(peerId, peerInfo.mMac);
                        }
                    }
                }
            }

            try {
                if (VDBG) Log.v(TAG, "requestMacAddresses: peerIdToMacMap=" + peerIdToMacMap);
                callback.macAddress(peerIdToMacMap);
            } catch (RemoteException e) {
                Log.e(TAG, "requestMacAddress (sync): exception on callback -- " + e);

            }
        });
    }

    /*
     * COMMANDS
     */

    /**
     * Place a request for delayed start operation on the state machine queue.
     */
    public void delayedInitialization() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_DELAYED_INITIALIZATION;
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to get the Wi-Fi Aware interface (before which no HAL command can be
     * executed).
     */
    public void getAwareInterface() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_GET_AWARE;
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to release the Wi-Fi Aware interface (after which no HAL command can be
     * executed).
     */
    public void releaseAwareInterface() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_RELEASE_AWARE;
        mSm.sendMessage(msg);
    }

    /**
     * Place a request for a new client connection on the state machine queue.
     */
    public void connect(int clientId, int uid, int pid, String callingPackage,
            IWifiAwareEventCallback callback, ConfigRequest configRequest,
            boolean notifyOnIdentityChanged) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_CONNECT;
        msg.arg2 = clientId;
        msg.obj = callback;
        msg.getData().putParcelable(MESSAGE_BUNDLE_KEY_CONFIG, configRequest);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_UID, uid);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_PID, pid);
        msg.getData().putString(MESSAGE_BUNDLE_KEY_CALLING_PACKAGE, callingPackage);
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_NOTIFY_IDENTITY_CHANGE,
                notifyOnIdentityChanged);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to disconnect (destroy) an existing client on the state
     * machine queue.
     */
    public void disconnect(int clientId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_DISCONNECT;
        msg.arg2 = clientId;
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to reconfigure Aware. No additional input - intended to use current
     * power settings when executed. Thus possibly entering or exiting power saving mode if
     * needed (or do nothing if Aware is not active).
     */
    public void reconfigure() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_RECONFIGURE;
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to stop a discovery session on the state machine queue.
     */
    public void terminateSession(int clientId, int sessionId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_TERMINATE_SESSION;
        msg.arg2 = clientId;
        msg.obj = sessionId;
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to start a new publish discovery session on the state
     * machine queue.
     */
    public void publish(int clientId, PublishConfig publishConfig,
            IWifiAwareDiscoverySessionCallback callback) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_PUBLISH;
        msg.arg2 = clientId;
        msg.obj = callback;
        msg.getData().putParcelable(MESSAGE_BUNDLE_KEY_CONFIG, publishConfig);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to modify an existing publish discovery session on the
     * state machine queue.
     */
    public void updatePublish(int clientId, int sessionId, PublishConfig publishConfig) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_UPDATE_PUBLISH;
        msg.arg2 = clientId;
        msg.obj = publishConfig;
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_SESSION_ID, sessionId);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to start a new subscribe discovery session on the state
     * machine queue.
     */
    public void subscribe(int clientId, SubscribeConfig subscribeConfig,
            IWifiAwareDiscoverySessionCallback callback) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_SUBSCRIBE;
        msg.arg2 = clientId;
        msg.obj = callback;
        msg.getData().putParcelable(MESSAGE_BUNDLE_KEY_CONFIG, subscribeConfig);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to modify an existing subscribe discovery session on the
     * state machine queue.
     */
    public void updateSubscribe(int clientId, int sessionId, SubscribeConfig subscribeConfig) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_UPDATE_SUBSCRIBE;
        msg.arg2 = clientId;
        msg.obj = subscribeConfig;
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_SESSION_ID, sessionId);
        mSm.sendMessage(msg);
    }

    /**
     * Place a request to send a message on a discovery session on the state
     * machine queue.
     */
    public void sendMessage(int clientId, int sessionId, int peerId, byte[] message, int messageId,
            int retryCount) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_ENQUEUE_SEND_MESSAGE;
        msg.arg2 = clientId;
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_SESSION_ID, sessionId);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_MESSAGE_PEER_ID, peerId);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE, message);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_MESSAGE_ID, messageId);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_RETRY_COUNT, retryCount);
        mSm.sendMessage(msg);
    }

    /**
     * Enable usage of Aware. Doesn't actually turn on Aware (form clusters) - that
     * only happens when a connection is created.
     */
    public void enableUsage() {
        if (mSettableParameters.get(PARAM_ON_IDLE_DISABLE_AWARE) != 0
                && mPowerManager.isDeviceIdleMode()) {
            if (mDbg) Log.d(TAG, "enableUsage(): while device is in IDLE mode - ignoring");
            return;
        }
        if (!mLocationManager.isLocationEnabled()) {
            if (mDbg) Log.d(TAG, "enableUsage(): while location is disabled - ignoring");
            return;
        }
        if (mWifiManager.getWifiState() != WifiManager.WIFI_STATE_ENABLED) {
            if (mDbg) Log.d(TAG, "enableUsage(): while Wi-Fi is disabled - ignoring");
            return;
        }
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_ENABLE_USAGE;
        mSm.sendMessage(msg);
    }

    /**
     * Disable usage of Aware. Terminates all existing clients with onAwareDown().
     */
    public void disableUsage() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_DISABLE_USAGE;
        mSm.sendMessage(msg);
    }

    /**
     * Checks whether Aware usage is enabled (not necessarily that Aware is up right
     * now) or disabled.
     *
     * @return A boolean indicating whether Aware usage is enabled (true) or
     *         disabled (false).
     */
    public boolean isUsageEnabled() {
        return mUsageEnabled;
    }

    /**
     * Get the capabilities of the current Aware firmware.
     */
    public void queryCapabilities() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_GET_CAPABILITIES;
        mSm.sendMessage(msg);
    }

    /**
     * Create all Aware data path interfaces which are supported by the firmware capabilities.
     */
    public void createAllDataPathInterfaces() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_CREATE_ALL_DATA_PATH_INTERFACES;
        mSm.sendMessage(msg);
    }

    /**
     * delete all Aware data path interfaces.
     */
    public void deleteAllDataPathInterfaces() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_DELETE_ALL_DATA_PATH_INTERFACES;
        mSm.sendMessage(msg);
    }

    /**
     * Create the specified data-path interface. Doesn't actually creates a data-path.
     */
    public void createDataPathInterface(String interfaceName) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_CREATE_DATA_PATH_INTERFACE;
        msg.obj = interfaceName;
        mSm.sendMessage(msg);
    }

    /**
     * Deletes the specified data-path interface.
     */
    public void deleteDataPathInterface(String interfaceName) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_DELETE_DATA_PATH_INTERFACE;
        msg.obj = interfaceName;
        mSm.sendMessage(msg);
    }

    /**
     * Command to initiate a data-path (executed by the initiator).
     */
    public void initiateDataPathSetup(WifiAwareNetworkSpecifier networkSpecifier, int peerId,
            int channelRequestType, int channel, byte[] peer, String interfaceName, byte[] pmk,
            String passphrase, boolean isOutOfBand) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_INITIATE_DATA_PATH_SETUP;
        msg.obj = networkSpecifier;
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_PEER_ID, peerId);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_CHANNEL_REQ_TYPE, channelRequestType);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_CHANNEL, channel);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS, peer);
        msg.getData().putString(MESSAGE_BUNDLE_KEY_INTERFACE_NAME, interfaceName);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_PMK, pmk);
        msg.getData().putString(MESSAGE_BUNDLE_KEY_PASSPHRASE, passphrase);
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_OOB, isOutOfBand);
        mSm.sendMessage(msg);
    }

    /**
     * Command to respond to the data-path request (executed by the responder).
     */
    public void respondToDataPathRequest(boolean accept, int ndpId, String interfaceName,
            byte[] pmk, String passphrase, boolean isOutOfBand) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_RESPOND_TO_DATA_PATH_SETUP_REQUEST;
        msg.arg2 = ndpId;
        msg.obj = accept;
        msg.getData().putString(MESSAGE_BUNDLE_KEY_INTERFACE_NAME, interfaceName);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_PMK, pmk);
        msg.getData().putString(MESSAGE_BUNDLE_KEY_PASSPHRASE, passphrase);
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_OOB, isOutOfBand);
        mSm.sendMessage(msg);
    }

    /**
     * Command to terminate the specified data-path.
     */
    public void endDataPath(int ndpId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_END_DATA_PATH;
        msg.arg2 = ndpId;
        mSm.sendMessage(msg);
    }

    /**
     * Aware follow-on messages (L2 messages) are queued by the firmware for transmission
     * on-the-air. The firmware has limited queue depth. The host queues all messages and doles
     * them out to the firmware when possible. This command removes the next messages for
     * transmission from the host queue and attempts to send it through the firmware. The queues
     * are inspected when the command is executed - not when the command is placed on the handler
     * (i.e. not evaluated here).
     */
    private void transmitNextMessage() {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_COMMAND);
        msg.arg1 = COMMAND_TYPE_TRANSMIT_NEXT_MESSAGE;
        mSm.sendMessage(msg);
    }

    /*
     * RESPONSES
     */

    /**
     * Place a callback request on the state machine queue: configuration
     * request completed (successfully).
     */
    public void onConfigSuccessResponse(short transactionId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_CONFIG_SUCCESS;
        msg.arg2 = transactionId;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: configuration
     * request failed.
     */
    public void onConfigFailedResponse(short transactionId, int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_CONFIG_FAIL;
        msg.arg2 = transactionId;
        msg.obj = reason;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the stage machine queue: disable request finished
     * (with the provided reason code).
     */
    public void onDisableResponse(short transactionId, int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_DISABLE;
        msg.arg2 = transactionId;
        msg.obj = reason;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: session
     * configuration (new or update) request succeeded.
     */
    public void onSessionConfigSuccessResponse(short transactionId, boolean isPublish,
            byte pubSubId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_SESSION_CONFIG_SUCCESS;
        msg.arg2 = transactionId;
        msg.obj = pubSubId;
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE, isPublish);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: session
     * configuration (new or update) request failed.
     */
    public void onSessionConfigFailResponse(short transactionId, boolean isPublish, int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_SESSION_CONFIG_FAIL;
        msg.arg2 = transactionId;
        msg.obj = reason;
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE, isPublish);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: message has been queued successfully.
     */
    public void onMessageSendQueuedSuccessResponse(short transactionId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_MESSAGE_SEND_QUEUED_SUCCESS;
        msg.arg2 = transactionId;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: attempt to queue the message failed.
     */
    public void onMessageSendQueuedFailResponse(short transactionId, int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_MESSAGE_SEND_QUEUED_FAIL;
        msg.arg2 = transactionId;
        msg.obj = reason;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: update vendor
     * capabilities of the Aware stack.
     */
    public void onCapabilitiesUpdateResponse(short transactionId,
            Capabilities capabilities) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_CAPABILITIES_UPDATED;
        msg.arg2 = transactionId;
        msg.obj = capabilities;
        mSm.sendMessage(msg);
    }

    /**
     * Places a callback request on the state machine queue: data-path interface creation command
     * completed.
     */
    public void onCreateDataPathInterfaceResponse(short transactionId, boolean success,
            int reasonOnFailure) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_CREATE_INTERFACE;
        msg.arg2 = transactionId;
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG, success);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_STATUS_CODE, reasonOnFailure);
        mSm.sendMessage(msg);
    }

    /**
     * Places a callback request on the state machine queue: data-path interface deletion command
     * completed.
     */
    public void onDeleteDataPathInterfaceResponse(short transactionId, boolean success,
            int reasonOnFailure) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_DELETE_INTERFACE;
        msg.arg2 = transactionId;
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG, success);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_STATUS_CODE, reasonOnFailure);
        mSm.sendMessage(msg);
    }

    /**
     * Response from firmware to initiateDataPathSetup(...). Indicates that command has started
     * succesfully (not completed!).
     */
    public void onInitiateDataPathResponseSuccess(short transactionId, int ndpId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_INITIATE_DATA_PATH_SUCCESS;
        msg.arg2 = transactionId;
        msg.obj = ndpId;
        mSm.sendMessage(msg);
    }

    /**
     * Response from firmware to initiateDataPathSetup(...).
     * Indicates that command has failed.
     */
    public void onInitiateDataPathResponseFail(short transactionId, int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_INITIATE_DATA_PATH_FAIL;
        msg.arg2 = transactionId;
        msg.obj = reason;
        mSm.sendMessage(msg);
    }

    /**
     * Response from firmware to
     * {@link #respondToDataPathRequest(boolean, int, String, byte[], String, boolean)}
     */
    public void onRespondToDataPathSetupRequestResponse(short transactionId, boolean success,
            int reasonOnFailure) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_RESPOND_TO_DATA_PATH_SETUP_REQUEST;
        msg.arg2 = transactionId;
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG, success);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_STATUS_CODE, reasonOnFailure);
        mSm.sendMessage(msg);
    }

    /**
     * Response from firmware to {@link #endDataPath(int)}.
     */
    public void onEndDataPathResponse(short transactionId, boolean success, int reasonOnFailure) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_RESPONSE);
        msg.arg1 = RESPONSE_TYPE_ON_END_DATA_PATH;
        msg.arg2 = transactionId;
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG, success);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_STATUS_CODE, reasonOnFailure);
        mSm.sendMessage(msg);
    }

    /*
     * NOTIFICATIONS
     */

    /**
     * Place a callback request on the state machine queue: the discovery
     * interface has changed.
     */
    public void onInterfaceAddressChangeNotification(byte[] mac) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_INTERFACE_CHANGE;
        msg.obj = mac;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: the cluster
     * membership has changed (e.g. due to starting a new cluster or joining
     * another cluster).
     */
    public void onClusterChangeNotification(int flag, byte[] clusterId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_CLUSTER_CHANGE;
        msg.arg2 = flag;
        msg.obj = clusterId;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: a discovery match
     * has occurred - e.g. our subscription discovered someone else publishing a
     * matching service (to the one we were looking for).
     */
    public void onMatchNotification(int pubSubId, int requestorInstanceId, byte[] peerMac,
            byte[] serviceSpecificInfo, byte[] matchFilter, int rangingIndication, int rangeMm) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_MATCH;
        msg.arg2 = pubSubId;
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_REQ_INSTANCE_ID, requestorInstanceId);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS, peerMac);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_SSI_DATA, serviceSpecificInfo);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_FILTER_DATA, matchFilter);
        msg.getData().putInt(MESSAGE_RANGING_INDICATION, rangingIndication);
        msg.getData().putInt(MESSAGE_RANGE_MM, rangeMm);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: a session (publish
     * or subscribe) has terminated (per plan or due to an error).
     */
    public void onSessionTerminatedNotification(int pubSubId, int reason, boolean isPublish) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_SESSION_TERMINATED;
        msg.arg2 = pubSubId;
        msg.obj = reason;
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE, isPublish);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: a message has been
     * received as part of a discovery session.
     */
    public void onMessageReceivedNotification(int pubSubId, int requestorInstanceId, byte[] peerMac,
            byte[] message) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_MESSAGE_RECEIVED;
        msg.arg2 = pubSubId;
        msg.obj = requestorInstanceId;
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS, peerMac);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE_DATA, message);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: Aware is going down.
     */
    public void onAwareDownNotification(int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_AWARE_DOWN;
        msg.arg2 = reason;
        mSm.sendMessage(msg);
    }

    /**
     * Notification that a message has been sent successfully (i.e. an ACK has been received).
     */
    public void onMessageSendSuccessNotification(short transactionId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_ON_MESSAGE_SEND_SUCCESS;
        msg.arg2 = transactionId;
        mSm.sendMessage(msg);
    }

    /**
     * Notification that a message transmission has failed due to the indicated reason - e.g. no ACK
     * was received.
     */
    public void onMessageSendFailNotification(short transactionId, int reason) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_ON_MESSAGE_SEND_FAIL;
        msg.arg2 = transactionId;
        msg.obj = reason;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: data-path request (from peer) received.
     */
    public void onDataPathRequestNotification(int pubSubId, byte[] mac, int ndpId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_ON_DATA_PATH_REQUEST;
        msg.arg2 = pubSubId;
        msg.obj = ndpId;
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS, mac);
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: data-path confirmation received - i.e.
     * data-path is now up.
     */
    public void onDataPathConfirmNotification(int ndpId, byte[] mac, boolean accept, int reason,
            byte[] message, List<NanDataPathChannelInfo> channelInfo) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_ON_DATA_PATH_CONFIRM;
        msg.arg2 = ndpId;
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS, mac);
        msg.getData().putBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG, accept);
        msg.getData().putInt(MESSAGE_BUNDLE_KEY_STATUS_CODE, reason);
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MESSAGE_DATA, message);
        msg.obj = channelInfo;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: the specified data-path has been
     * terminated.
     */
    public void onDataPathEndNotification(int ndpId) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_ON_DATA_PATH_END;
        msg.arg2 = ndpId;
        mSm.sendMessage(msg);
    }

    /**
     * Place a callback request on the state machine queue: schedule update for the specified
     * data-paths.
     */
    public void onDataPathScheduleUpdateNotification(byte[] peerMac, ArrayList<Integer> ndpIds,
            List<NanDataPathChannelInfo> channelInfo) {
        Message msg = mSm.obtainMessage(MESSAGE_TYPE_NOTIFICATION);
        msg.arg1 = NOTIFICATION_TYPE_ON_DATA_PATH_SCHED_UPDATE;
        msg.getData().putByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS, peerMac);
        msg.getData().putIntegerArrayList(MESSAGE_BUNDLE_KEY_NDP_IDS, ndpIds);
        msg.obj = channelInfo;
        mSm.sendMessage(msg);
    }

    /**
     * State machine.
     */
    @VisibleForTesting
    class WifiAwareStateMachine extends StateMachine {
        private static final int TRANSACTION_ID_IGNORE = 0;

        private DefaultState mDefaultState = new DefaultState();
        private WaitState mWaitState = new WaitState();
        private WaitForResponseState mWaitForResponseState = new WaitForResponseState();

        private short mNextTransactionId = 1;
        public int mNextSessionId = 1;

        private Message mCurrentCommand;
        private short mCurrentTransactionId = TRANSACTION_ID_IGNORE;

        private static final long AWARE_SEND_MESSAGE_TIMEOUT = 10_000;
        private int mSendArrivalSequenceCounter = 0;
        private boolean mSendQueueBlocked = false;
        private final SparseArray<Message> mHostQueuedSendMessages = new SparseArray<>();
        private final Map<Short, Message> mFwQueuedSendMessages = new LinkedHashMap<>();
        private WakeupMessage mSendMessageTimeoutMessage = new WakeupMessage(mContext, getHandler(),
                HAL_SEND_MESSAGE_TIMEOUT_TAG, MESSAGE_TYPE_SEND_MESSAGE_TIMEOUT);

        private static final long AWARE_WAIT_FOR_DP_CONFIRM_TIMEOUT = 20_000;
        private final Map<WifiAwareNetworkSpecifier, WakeupMessage>
                mDataPathConfirmTimeoutMessages = new ArrayMap<>();

        WifiAwareStateMachine(String name, Looper looper) {
            super(name, looper);

            addState(mDefaultState);
            /* --> */ addState(mWaitState, mDefaultState);
            /* --> */ addState(mWaitForResponseState, mDefaultState);

            setInitialState(mWaitState);
        }

        public void onAwareDownCleanupSendQueueState() {
            mSendQueueBlocked = false;
            mHostQueuedSendMessages.clear();
            mFwQueuedSendMessages.clear();
        }

        private class DefaultState extends State {
            @Override
            public boolean processMessage(Message msg) {
                if (VDBG) {
                    Log.v(TAG, getName() + msg.toString());
                }

                switch (msg.what) {
                    case MESSAGE_TYPE_NOTIFICATION:
                        processNotification(msg);
                        return HANDLED;
                    case MESSAGE_TYPE_SEND_MESSAGE_TIMEOUT:
                        processSendMessageTimeout();
                        return HANDLED;
                    case MESSAGE_TYPE_DATA_PATH_TIMEOUT: {
                        WifiAwareNetworkSpecifier networkSpecifier =
                                (WifiAwareNetworkSpecifier) msg.obj;

                        if (mDbg) {
                            Log.v(TAG, "MESSAGE_TYPE_DATA_PATH_TIMEOUT: networkSpecifier="
                                    + networkSpecifier);
                        }

                        mDataPathMgr.handleDataPathTimeout(networkSpecifier);
                        mDataPathConfirmTimeoutMessages.remove(networkSpecifier);
                        return HANDLED;
                    }
                    default:
                        /* fall-through */
                }

                Log.wtf(TAG,
                        "DefaultState: should not get non-NOTIFICATION in this state: msg=" + msg);
                return NOT_HANDLED;
            }
        }

        private class WaitState extends State {
            @Override
            public boolean processMessage(Message msg) {
                if (VDBG) {
                    Log.v(TAG, getName() + msg.toString());
                }

                switch (msg.what) {
                    case MESSAGE_TYPE_COMMAND:
                        if (processCommand(msg)) {
                            transitionTo(mWaitForResponseState);
                        }
                        return HANDLED;
                    case MESSAGE_TYPE_RESPONSE:
                        /* fall-through */
                    case MESSAGE_TYPE_RESPONSE_TIMEOUT:
                        /*
                         * remnants/delayed/out-of-sync messages - but let
                         * WaitForResponseState deal with them (identified as
                         * out-of-date by transaction ID).
                         */
                        deferMessage(msg);
                        return HANDLED;
                    default:
                        /* fall-through */
                }

                return NOT_HANDLED;
            }
        }

        private class WaitForResponseState extends State {
            private static final long AWARE_COMMAND_TIMEOUT = 5_000;
            private WakeupMessage mTimeoutMessage;

            @Override
            public void enter() {
                mTimeoutMessage = new WakeupMessage(mContext, getHandler(), HAL_COMMAND_TIMEOUT_TAG,
                        MESSAGE_TYPE_RESPONSE_TIMEOUT, mCurrentCommand.arg1, mCurrentTransactionId);
                mTimeoutMessage.schedule(SystemClock.elapsedRealtime() + AWARE_COMMAND_TIMEOUT);
            }

            @Override
            public void exit() {
                mTimeoutMessage.cancel();
            }

            @Override
            public boolean processMessage(Message msg) {
                if (VDBG) {
                    Log.v(TAG, getName() + msg.toString());
                }

                switch (msg.what) {
                    case MESSAGE_TYPE_COMMAND:
                        /*
                         * don't want COMMANDs in this state - defer until back
                         * in WaitState
                         */
                        deferMessage(msg);
                        return HANDLED;
                    case MESSAGE_TYPE_RESPONSE:
                        if (msg.arg2 == mCurrentTransactionId) {
                            processResponse(msg);
                            transitionTo(mWaitState);
                        } else {
                            Log.w(TAG,
                                    "WaitForResponseState: processMessage: non-matching "
                                            + "transaction ID on RESPONSE (a very late "
                                            + "response) -- msg=" + msg);
                            /* no transition */
                        }
                        return HANDLED;
                    case MESSAGE_TYPE_RESPONSE_TIMEOUT:
                        if (msg.arg2 == mCurrentTransactionId) {
                            processTimeout(msg);
                            transitionTo(mWaitState);
                        } else {
                            Log.w(TAG, "WaitForResponseState: processMessage: non-matching "
                                    + "transaction ID on RESPONSE_TIMEOUT (either a non-cancelled "
                                    + "timeout or a race condition with cancel) -- msg=" + msg);
                            /* no transition */
                        }
                        return HANDLED;
                    default:
                        /* fall-through */
                }

                return NOT_HANDLED;
            }
        }

        private void processNotification(Message msg) {
            if (VDBG) {
                Log.v(TAG, "processNotification: msg=" + msg);
            }

            switch (msg.arg1) {
                case NOTIFICATION_TYPE_INTERFACE_CHANGE: {
                    byte[] mac = (byte[]) msg.obj;

                    onInterfaceAddressChangeLocal(mac);
                    break;
                }
                case NOTIFICATION_TYPE_CLUSTER_CHANGE: {
                    int flag = msg.arg2;
                    byte[] clusterId = (byte[]) msg.obj;

                    onClusterChangeLocal(flag, clusterId);
                    break;
                }
                case NOTIFICATION_TYPE_MATCH: {
                    int pubSubId = msg.arg2;
                    int requestorInstanceId = msg.getData()
                            .getInt(MESSAGE_BUNDLE_KEY_REQ_INSTANCE_ID);
                    byte[] peerMac = msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS);
                    byte[] serviceSpecificInfo = msg.getData()
                            .getByteArray(MESSAGE_BUNDLE_KEY_SSI_DATA);
                    byte[] matchFilter = msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_FILTER_DATA);
                    int rangingIndication = msg.getData().getInt(MESSAGE_RANGING_INDICATION);
                    int rangeMm = msg.getData().getInt(MESSAGE_RANGE_MM);

                    onMatchLocal(pubSubId, requestorInstanceId, peerMac, serviceSpecificInfo,
                            matchFilter, rangingIndication, rangeMm);
                    break;
                }
                case NOTIFICATION_TYPE_SESSION_TERMINATED: {
                    int pubSubId = msg.arg2;
                    int reason = (Integer) msg.obj;
                    boolean isPublish = msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE);

                    onSessionTerminatedLocal(pubSubId, isPublish, reason);
                    break;
                }
                case NOTIFICATION_TYPE_MESSAGE_RECEIVED: {
                    int pubSubId = msg.arg2;
                    int requestorInstanceId = (Integer) msg.obj;
                    byte[] peerMac = msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS);
                    byte[] message = msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE_DATA);

                    onMessageReceivedLocal(pubSubId, requestorInstanceId, peerMac, message);
                    break;
                }
                case NOTIFICATION_TYPE_AWARE_DOWN: {
                    int reason = msg.arg2;

                    /*
                     * TODO: b/28615938. Use reason code to determine whether or not need clean-up
                     * local state (only needed if AWARE_DOWN is due to internal firmware reason,
                     * e.g. concurrency, rather than due to a requested shutdown).
                     */

                    onAwareDownLocal();

                    break;
                }
                case NOTIFICATION_TYPE_ON_MESSAGE_SEND_SUCCESS: {
                    short transactionId = (short) msg.arg2;
                    Message queuedSendCommand = mFwQueuedSendMessages.get(transactionId);
                    if (VDBG) {
                        Log.v(TAG, "NOTIFICATION_TYPE_ON_MESSAGE_SEND_SUCCESS: queuedSendCommand="
                                + queuedSendCommand);
                    }
                    if (queuedSendCommand == null) {
                        Log.w(TAG,
                                "processNotification: NOTIFICATION_TYPE_ON_MESSAGE_SEND_SUCCESS:"
                                        + " transactionId=" + transactionId
                                        + " - no such queued send command (timed-out?)");
                    } else {
                        mFwQueuedSendMessages.remove(transactionId);
                        updateSendMessageTimeout();
                        onMessageSendSuccessLocal(queuedSendCommand);
                    }
                    mSendQueueBlocked = false;
                    transmitNextMessage();

                    break;
                }
                case NOTIFICATION_TYPE_ON_MESSAGE_SEND_FAIL: {
                    short transactionId = (short) msg.arg2;
                    int reason = (Integer) msg.obj;
                    Message sentMessage = mFwQueuedSendMessages.get(transactionId);
                    if (VDBG) {
                        Log.v(TAG, "NOTIFICATION_TYPE_ON_MESSAGE_SEND_FAIL: sentMessage="
                                + sentMessage);
                    }
                    if (sentMessage == null) {
                        Log.w(TAG,
                                "processNotification: NOTIFICATION_TYPE_ON_MESSAGE_SEND_FAIL:"
                                        + " transactionId=" + transactionId
                                        + " - no such queued send command (timed-out?)");
                    } else {
                        mFwQueuedSendMessages.remove(transactionId);
                        updateSendMessageTimeout();

                        int retryCount = sentMessage.getData()
                                .getInt(MESSAGE_BUNDLE_KEY_RETRY_COUNT);
                        if (retryCount > 0 && reason == NanStatusType.NO_OTA_ACK) {
                            if (VDBG) {
                                Log.v(TAG,
                                        "NOTIFICATION_TYPE_ON_MESSAGE_SEND_FAIL: transactionId="
                                                + transactionId + ", reason=" + reason
                                                + ": retransmitting - retryCount=" + retryCount);
                            }
                            sentMessage.getData().putInt(MESSAGE_BUNDLE_KEY_RETRY_COUNT,
                                    retryCount - 1);

                            int arrivalSeq = sentMessage.getData().getInt(
                                    MESSAGE_BUNDLE_KEY_MESSAGE_ARRIVAL_SEQ);
                            mHostQueuedSendMessages.put(arrivalSeq, sentMessage);
                        } else {
                            onMessageSendFailLocal(sentMessage, reason);
                        }
                        mSendQueueBlocked = false;
                        transmitNextMessage();
                    }
                    break;
                }
                case NOTIFICATION_TYPE_ON_DATA_PATH_REQUEST: {
                    WifiAwareNetworkSpecifier networkSpecifier = mDataPathMgr.onDataPathRequest(
                            msg.arg2, msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS),
                            (int) msg.obj);

                    if (networkSpecifier != null) {
                        WakeupMessage timeout = new WakeupMessage(mContext, getHandler(),
                                HAL_DATA_PATH_CONFIRM_TIMEOUT_TAG, MESSAGE_TYPE_DATA_PATH_TIMEOUT,
                                0, 0, networkSpecifier);
                        mDataPathConfirmTimeoutMessages.put(networkSpecifier, timeout);
                        timeout.schedule(
                                SystemClock.elapsedRealtime() + AWARE_WAIT_FOR_DP_CONFIRM_TIMEOUT);
                    }

                    break;
                }
                case NOTIFICATION_TYPE_ON_DATA_PATH_CONFIRM: {
                    WifiAwareNetworkSpecifier networkSpecifier = mDataPathMgr.onDataPathConfirm(
                            msg.arg2, msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS),
                            msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG),
                            msg.getData().getInt(MESSAGE_BUNDLE_KEY_STATUS_CODE),
                            msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE_DATA),
                            (List<NanDataPathChannelInfo>) msg.obj);

                    if (networkSpecifier != null) {
                        WakeupMessage timeout = mDataPathConfirmTimeoutMessages.remove(
                                networkSpecifier);
                        if (timeout != null) {
                            timeout.cancel();
                        }
                    }

                    break;
                }
                case NOTIFICATION_TYPE_ON_DATA_PATH_END:
                    mDataPathMgr.onDataPathEnd(msg.arg2);
                    break;
                case NOTIFICATION_TYPE_ON_DATA_PATH_SCHED_UPDATE:
                    mDataPathMgr.onDataPathSchedUpdate(
                            msg.getData().getByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS),
                            msg.getData().getIntegerArrayList(MESSAGE_BUNDLE_KEY_NDP_IDS),
                            (List<NanDataPathChannelInfo>) msg.obj);
                    break;
                default:
                    Log.wtf(TAG, "processNotification: this isn't a NOTIFICATION -- msg=" + msg);
                    return;
            }
        }

        /**
         * Execute the command specified by the input Message. Returns a true if
         * need to wait for a RESPONSE, otherwise a false. We may not have to
         * wait for a RESPONSE if there was an error in the state (so no command
         * is sent to HAL) OR if we choose not to wait for response - e.g. for
         * disconnected/terminate commands failure is not possible.
         */
        private boolean processCommand(Message msg) {
            if (VDBG) {
                Log.v(TAG, "processCommand: msg=" + msg);
            }

            if (mCurrentCommand != null) {
                Log.wtf(TAG,
                        "processCommand: receiving a command (msg=" + msg
                                + ") but current (previous) command isn't null (prev_msg="
                                + mCurrentCommand + ")");
                mCurrentCommand = null;
            }

            mCurrentTransactionId = mNextTransactionId++;

            boolean waitForResponse = true;

            switch (msg.arg1) {
                case COMMAND_TYPE_CONNECT: {
                    int clientId = msg.arg2;
                    IWifiAwareEventCallback callback = (IWifiAwareEventCallback) msg.obj;
                    ConfigRequest configRequest = (ConfigRequest) msg.getData()
                            .getParcelable(MESSAGE_BUNDLE_KEY_CONFIG);
                    int uid = msg.getData().getInt(MESSAGE_BUNDLE_KEY_UID);
                    int pid = msg.getData().getInt(MESSAGE_BUNDLE_KEY_PID);
                    String callingPackage = msg.getData().getString(
                            MESSAGE_BUNDLE_KEY_CALLING_PACKAGE);
                    boolean notifyIdentityChange = msg.getData().getBoolean(
                            MESSAGE_BUNDLE_KEY_NOTIFY_IDENTITY_CHANGE);

                    waitForResponse = connectLocal(mCurrentTransactionId, clientId, uid, pid,
                            callingPackage, callback, configRequest, notifyIdentityChange);
                    break;
                }
                case COMMAND_TYPE_DISCONNECT: {
                    int clientId = msg.arg2;

                    waitForResponse = disconnectLocal(mCurrentTransactionId, clientId);
                    break;
                }
                case COMMAND_TYPE_RECONFIGURE:
                    waitForResponse = reconfigureLocal(mCurrentTransactionId);
                    break;
                case COMMAND_TYPE_TERMINATE_SESSION: {
                    int clientId = msg.arg2;
                    int sessionId = (Integer) msg.obj;

                    terminateSessionLocal(clientId, sessionId);
                    waitForResponse = false;
                    break;
                }
                case COMMAND_TYPE_PUBLISH: {
                    int clientId = msg.arg2;
                    IWifiAwareDiscoverySessionCallback callback =
                            (IWifiAwareDiscoverySessionCallback) msg.obj;
                    PublishConfig publishConfig = (PublishConfig) msg.getData()
                            .getParcelable(MESSAGE_BUNDLE_KEY_CONFIG);

                    waitForResponse = publishLocal(mCurrentTransactionId, clientId, publishConfig,
                            callback);
                    break;
                }
                case COMMAND_TYPE_UPDATE_PUBLISH: {
                    int clientId = msg.arg2;
                    int sessionId = msg.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);
                    PublishConfig publishConfig = (PublishConfig) msg.obj;

                    waitForResponse = updatePublishLocal(mCurrentTransactionId, clientId, sessionId,
                            publishConfig);
                    break;
                }
                case COMMAND_TYPE_SUBSCRIBE: {
                    int clientId = msg.arg2;
                    IWifiAwareDiscoverySessionCallback callback =
                            (IWifiAwareDiscoverySessionCallback) msg.obj;
                    SubscribeConfig subscribeConfig = (SubscribeConfig) msg.getData()
                            .getParcelable(MESSAGE_BUNDLE_KEY_CONFIG);

                    waitForResponse = subscribeLocal(mCurrentTransactionId, clientId,
                            subscribeConfig, callback);
                    break;
                }
                case COMMAND_TYPE_UPDATE_SUBSCRIBE: {
                    int clientId = msg.arg2;
                    int sessionId = msg.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);
                    SubscribeConfig subscribeConfig = (SubscribeConfig) msg.obj;

                    waitForResponse = updateSubscribeLocal(mCurrentTransactionId, clientId,
                            sessionId, subscribeConfig);
                    break;
                }
                case COMMAND_TYPE_ENQUEUE_SEND_MESSAGE: {
                    if (VDBG) {
                        Log.v(TAG, "processCommand: ENQUEUE_SEND_MESSAGE - messageId="
                                + msg.getData().getInt(MESSAGE_BUNDLE_KEY_MESSAGE_ID)
                                + ", mSendArrivalSequenceCounter=" + mSendArrivalSequenceCounter);
                    }
                    Message sendMsg = obtainMessage(msg.what);
                    sendMsg.copyFrom(msg);
                    sendMsg.getData().putInt(MESSAGE_BUNDLE_KEY_MESSAGE_ARRIVAL_SEQ,
                            mSendArrivalSequenceCounter);
                    mHostQueuedSendMessages.put(mSendArrivalSequenceCounter, sendMsg);
                    mSendArrivalSequenceCounter++;
                    waitForResponse = false;

                    if (!mSendQueueBlocked) {
                        transmitNextMessage();
                    }

                    break;
                }
                case COMMAND_TYPE_TRANSMIT_NEXT_MESSAGE: {
                    if (mSendQueueBlocked || mHostQueuedSendMessages.size() == 0) {
                        if (VDBG) {
                            Log.v(TAG, "processCommand: SEND_TOP_OF_QUEUE_MESSAGE - blocked or "
                                    + "empty host queue");
                        }
                        waitForResponse = false;
                    } else {
                        if (VDBG) {
                            Log.v(TAG, "processCommand: SEND_TOP_OF_QUEUE_MESSAGE - "
                                    + "sendArrivalSequenceCounter="
                                    + mHostQueuedSendMessages.keyAt(0));
                        }
                        Message sendMessage = mHostQueuedSendMessages.valueAt(0);
                        mHostQueuedSendMessages.removeAt(0);

                        Bundle data = sendMessage.getData();
                        int clientId = sendMessage.arg2;
                        int sessionId = sendMessage.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);
                        int peerId = data.getInt(MESSAGE_BUNDLE_KEY_MESSAGE_PEER_ID);
                        byte[] message = data.getByteArray(MESSAGE_BUNDLE_KEY_MESSAGE);
                        int messageId = data.getInt(MESSAGE_BUNDLE_KEY_MESSAGE_ID);

                        msg.getData().putParcelable(MESSAGE_BUNDLE_KEY_SENT_MESSAGE, sendMessage);

                        waitForResponse = sendFollowonMessageLocal(mCurrentTransactionId, clientId,
                                sessionId, peerId, message, messageId);
                    }
                    break;
                }
                case COMMAND_TYPE_ENABLE_USAGE:
                    enableUsageLocal();
                    waitForResponse = false;
                    break;
                case COMMAND_TYPE_DISABLE_USAGE:
                    waitForResponse = disableUsageLocal(mCurrentTransactionId);
                    break;
                case COMMAND_TYPE_GET_CAPABILITIES:
                    if (mCapabilities == null) {
                        waitForResponse = mWifiAwareNativeApi.getCapabilities(
                                mCurrentTransactionId);
                    } else {
                        if (VDBG) {
                            Log.v(TAG, "COMMAND_TYPE_GET_CAPABILITIES: already have capabilities - "
                                    + "skipping");
                        }
                        waitForResponse = false;
                    }
                    break;
                case COMMAND_TYPE_CREATE_ALL_DATA_PATH_INTERFACES:
                    mDataPathMgr.createAllInterfaces();
                    waitForResponse = false;
                    break;
                case COMMAND_TYPE_DELETE_ALL_DATA_PATH_INTERFACES:
                    mDataPathMgr.deleteAllInterfaces();
                    waitForResponse = false;
                    break;
                case COMMAND_TYPE_CREATE_DATA_PATH_INTERFACE:
                    waitForResponse = mWifiAwareNativeApi.createAwareNetworkInterface(
                            mCurrentTransactionId, (String) msg.obj);
                    break;
                case COMMAND_TYPE_DELETE_DATA_PATH_INTERFACE:
                    waitForResponse = mWifiAwareNativeApi.deleteAwareNetworkInterface(
                            mCurrentTransactionId, (String) msg.obj);
                    break;
                case COMMAND_TYPE_INITIATE_DATA_PATH_SETUP: {
                    Bundle data = msg.getData();

                    WifiAwareNetworkSpecifier networkSpecifier =
                            (WifiAwareNetworkSpecifier) msg.obj;

                    int peerId = data.getInt(MESSAGE_BUNDLE_KEY_PEER_ID);
                    int channelRequestType = data.getInt(MESSAGE_BUNDLE_KEY_CHANNEL_REQ_TYPE);
                    int channel = data.getInt(MESSAGE_BUNDLE_KEY_CHANNEL);
                    byte[] peer = data.getByteArray(MESSAGE_BUNDLE_KEY_MAC_ADDRESS);
                    String interfaceName = data.getString(MESSAGE_BUNDLE_KEY_INTERFACE_NAME);
                    byte[] pmk = data.getByteArray(MESSAGE_BUNDLE_KEY_PMK);
                    String passphrase = data.getString(MESSAGE_BUNDLE_KEY_PASSPHRASE);
                    boolean isOutOfBand = data.getBoolean(MESSAGE_BUNDLE_KEY_OOB);

                    waitForResponse = initiateDataPathSetupLocal(mCurrentTransactionId,
                            networkSpecifier, peerId, channelRequestType, channel, peer,
                            interfaceName, pmk, passphrase, isOutOfBand);

                    if (waitForResponse) {
                        WakeupMessage timeout = new WakeupMessage(mContext, getHandler(),
                                HAL_DATA_PATH_CONFIRM_TIMEOUT_TAG, MESSAGE_TYPE_DATA_PATH_TIMEOUT,
                                0, 0, networkSpecifier);
                        mDataPathConfirmTimeoutMessages.put(networkSpecifier, timeout);
                        timeout.schedule(
                                SystemClock.elapsedRealtime() + AWARE_WAIT_FOR_DP_CONFIRM_TIMEOUT);
                    }
                    break;
                }
                case COMMAND_TYPE_RESPOND_TO_DATA_PATH_SETUP_REQUEST: {
                    Bundle data = msg.getData();

                    int ndpId = msg.arg2;
                    boolean accept = (boolean) msg.obj;
                    String interfaceName = data.getString(MESSAGE_BUNDLE_KEY_INTERFACE_NAME);
                    byte[] pmk = data.getByteArray(MESSAGE_BUNDLE_KEY_PMK);
                    String passphrase = data.getString(MESSAGE_BUNDLE_KEY_PASSPHRASE);
                    boolean isOutOfBand = data.getBoolean(MESSAGE_BUNDLE_KEY_OOB);

                    waitForResponse = respondToDataPathRequestLocal(mCurrentTransactionId, accept,
                            ndpId, interfaceName, pmk, passphrase, isOutOfBand);

                    break;
                }
                case COMMAND_TYPE_END_DATA_PATH:
                    waitForResponse = endDataPathLocal(mCurrentTransactionId, msg.arg2);
                    break;
                case COMMAND_TYPE_DELAYED_INITIALIZATION:
                    mWifiAwareNativeManager.start(getHandler());
                    waitForResponse = false;
                    break;
                case COMMAND_TYPE_GET_AWARE:
                    mWifiAwareNativeManager.tryToGetAware();
                    waitForResponse = false;
                    break;
                case COMMAND_TYPE_RELEASE_AWARE:
                    mWifiAwareNativeManager.releaseAware();
                    waitForResponse = false;
                    break;
                default:
                    waitForResponse = false;
                    Log.wtf(TAG, "processCommand: this isn't a COMMAND -- msg=" + msg);
                    /* fall-through */
            }

            if (!waitForResponse) {
                mCurrentTransactionId = TRANSACTION_ID_IGNORE;
            } else {
                mCurrentCommand = obtainMessage(msg.what);
                mCurrentCommand.copyFrom(msg);
            }

            return waitForResponse;
        }

        private void processResponse(Message msg) {
            if (VDBG) {
                Log.v(TAG, "processResponse: msg=" + msg);
            }

            if (mCurrentCommand == null) {
                Log.wtf(TAG, "processResponse: no existing command stored!? msg=" + msg);
                mCurrentTransactionId = TRANSACTION_ID_IGNORE;
                return;
            }

            switch (msg.arg1) {
                case RESPONSE_TYPE_ON_CONFIG_SUCCESS:
                    onConfigCompletedLocal(mCurrentCommand);
                    break;
                case RESPONSE_TYPE_ON_CONFIG_FAIL: {
                    int reason = (Integer) msg.obj;

                    onConfigFailedLocal(mCurrentCommand, reason);
                    break;
                }
                case RESPONSE_TYPE_ON_SESSION_CONFIG_SUCCESS: {
                    byte pubSubId = (Byte) msg.obj;
                    boolean isPublish = msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE);

                    onSessionConfigSuccessLocal(mCurrentCommand, pubSubId, isPublish);
                    break;
                }
                case RESPONSE_TYPE_ON_SESSION_CONFIG_FAIL: {
                    int reason = (Integer) msg.obj;
                    boolean isPublish = msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SESSION_TYPE);

                    onSessionConfigFailLocal(mCurrentCommand, isPublish, reason);
                    break;
                }
                case RESPONSE_TYPE_ON_MESSAGE_SEND_QUEUED_SUCCESS: {
                    Message sentMessage = mCurrentCommand.getData().getParcelable(
                            MESSAGE_BUNDLE_KEY_SENT_MESSAGE);
                    sentMessage.getData().putLong(MESSAGE_BUNDLE_KEY_SEND_MESSAGE_ENQUEUE_TIME,
                            SystemClock.elapsedRealtime());
                    mFwQueuedSendMessages.put(mCurrentTransactionId, sentMessage);
                    updateSendMessageTimeout();
                    if (!mSendQueueBlocked) {
                        transmitNextMessage();
                    }

                    if (VDBG) {
                        Log.v(TAG, "processResponse: ON_MESSAGE_SEND_QUEUED_SUCCESS - arrivalSeq="
                                + sentMessage.getData().getInt(
                                MESSAGE_BUNDLE_KEY_MESSAGE_ARRIVAL_SEQ));
                    }
                    break;
                }
                case RESPONSE_TYPE_ON_MESSAGE_SEND_QUEUED_FAIL: {
                    if (VDBG) {
                        Log.v(TAG, "processResponse: ON_MESSAGE_SEND_QUEUED_FAIL - blocking!");
                    }
                    int reason = (Integer) msg.obj;
                    if (reason == NanStatusType.FOLLOWUP_TX_QUEUE_FULL) {
                        Message sentMessage = mCurrentCommand.getData().getParcelable(
                                MESSAGE_BUNDLE_KEY_SENT_MESSAGE);
                        int arrivalSeq = sentMessage.getData().getInt(
                                MESSAGE_BUNDLE_KEY_MESSAGE_ARRIVAL_SEQ);
                        mHostQueuedSendMessages.put(arrivalSeq, sentMessage);
                        mSendQueueBlocked = true;

                        if (VDBG) {
                            Log.v(TAG, "processResponse: ON_MESSAGE_SEND_QUEUED_FAIL - arrivalSeq="
                                    + arrivalSeq + " -- blocking");
                        }
                    } else {
                        Message sentMessage = mCurrentCommand.getData().getParcelable(
                                MESSAGE_BUNDLE_KEY_SENT_MESSAGE);
                        onMessageSendFailLocal(sentMessage, NanStatusType.INTERNAL_FAILURE);
                        if (!mSendQueueBlocked) {
                            transmitNextMessage();
                        }
                    }
                    break;
                }
                case RESPONSE_TYPE_ON_CAPABILITIES_UPDATED: {
                    onCapabilitiesUpdatedResponseLocal((Capabilities) msg.obj);
                    break;
                }
                case RESPONSE_TYPE_ON_CREATE_INTERFACE:
                    onCreateDataPathInterfaceResponseLocal(mCurrentCommand,
                            msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG),
                            msg.getData().getInt(MESSAGE_BUNDLE_KEY_STATUS_CODE));
                    break;
                case RESPONSE_TYPE_ON_DELETE_INTERFACE:
                    onDeleteDataPathInterfaceResponseLocal(mCurrentCommand,
                            msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG),
                            msg.getData().getInt(MESSAGE_BUNDLE_KEY_STATUS_CODE));
                    break;
                case RESPONSE_TYPE_ON_INITIATE_DATA_PATH_SUCCESS:
                    onInitiateDataPathResponseSuccessLocal(mCurrentCommand, (int) msg.obj);
                    break;
                case RESPONSE_TYPE_ON_INITIATE_DATA_PATH_FAIL:
                    onInitiateDataPathResponseFailLocal(mCurrentCommand, (int) msg.obj);
                    break;
                case RESPONSE_TYPE_ON_RESPOND_TO_DATA_PATH_SETUP_REQUEST:
                    onRespondToDataPathSetupRequestResponseLocal(mCurrentCommand,
                            msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG),
                            msg.getData().getInt(MESSAGE_BUNDLE_KEY_STATUS_CODE));
                    break;
                case RESPONSE_TYPE_ON_END_DATA_PATH:
                    onEndPathEndResponseLocal(mCurrentCommand,
                            msg.getData().getBoolean(MESSAGE_BUNDLE_KEY_SUCCESS_FLAG),
                            msg.getData().getInt(MESSAGE_BUNDLE_KEY_STATUS_CODE));
                    break;
                case RESPONSE_TYPE_ON_DISABLE:
                    onDisableResponseLocal(mCurrentCommand, (Integer) msg.obj);
                    break;
                default:
                    Log.wtf(TAG, "processResponse: this isn't a RESPONSE -- msg=" + msg);
                    mCurrentCommand = null;
                    mCurrentTransactionId = TRANSACTION_ID_IGNORE;
                    return;
            }

            mCurrentCommand = null;
            mCurrentTransactionId = TRANSACTION_ID_IGNORE;
        }

        private void processTimeout(Message msg) {
            if (mDbg) {
                Log.v(TAG, "processTimeout: msg=" + msg);
            }

            if (mCurrentCommand == null) {
                Log.wtf(TAG, "processTimeout: no existing command stored!? msg=" + msg);
                mCurrentTransactionId = TRANSACTION_ID_IGNORE;
                return;
            }

            /*
             * Only have to handle those COMMANDs which wait for a response.
             */
            switch (msg.arg1) {
                case COMMAND_TYPE_CONNECT: {
                    onConfigFailedLocal(mCurrentCommand, NanStatusType.INTERNAL_FAILURE);
                    break;
                }
                case COMMAND_TYPE_DISCONNECT: {
                    onConfigFailedLocal(mCurrentCommand, NanStatusType.INTERNAL_FAILURE);
                    break;
                }
                case COMMAND_TYPE_RECONFIGURE:
                    /*
                     * Reconfigure timed-out. There is nothing to do but log the issue - which
                      * will be done in the callback.
                     */
                    onConfigFailedLocal(mCurrentCommand, NanStatusType.INTERNAL_FAILURE);
                    break;
                case COMMAND_TYPE_TERMINATE_SESSION: {
                    Log.wtf(TAG, "processTimeout: TERMINATE_SESSION - shouldn't be waiting!");
                    break;
                }
                case COMMAND_TYPE_PUBLISH: {
                    onSessionConfigFailLocal(mCurrentCommand, true, NanStatusType.INTERNAL_FAILURE);
                    break;
                }
                case COMMAND_TYPE_UPDATE_PUBLISH: {
                    onSessionConfigFailLocal(mCurrentCommand, true, NanStatusType.INTERNAL_FAILURE);
                    break;
                }
                case COMMAND_TYPE_SUBSCRIBE: {
                    onSessionConfigFailLocal(mCurrentCommand, false,
                            NanStatusType.INTERNAL_FAILURE);
                    break;
                }
                case COMMAND_TYPE_UPDATE_SUBSCRIBE: {
                    onSessionConfigFailLocal(mCurrentCommand, false,
                            NanStatusType.INTERNAL_FAILURE);
                    break;
                }
                case COMMAND_TYPE_ENQUEUE_SEND_MESSAGE: {
                    Log.wtf(TAG, "processTimeout: ENQUEUE_SEND_MESSAGE - shouldn't be waiting!");
                    break;
                }
                case COMMAND_TYPE_TRANSMIT_NEXT_MESSAGE: {
                    Message sentMessage = mCurrentCommand.getData().getParcelable(
                            MESSAGE_BUNDLE_KEY_SENT_MESSAGE);
                    onMessageSendFailLocal(sentMessage, NanStatusType.INTERNAL_FAILURE);
                    mSendQueueBlocked = false;
                    transmitNextMessage();
                    break;
                }
                case COMMAND_TYPE_ENABLE_USAGE:
                    Log.wtf(TAG, "processTimeout: ENABLE_USAGE - shouldn't be waiting!");
                    break;
                case COMMAND_TYPE_DISABLE_USAGE:
                    Log.wtf(TAG, "processTimeout: DISABLE_USAGE - shouldn't be waiting!");
                    break;
                case COMMAND_TYPE_GET_CAPABILITIES:
                    Log.e(TAG,
                            "processTimeout: GET_CAPABILITIES timed-out - strange, will try again"
                                    + " when next enabled!?");
                    break;
                case COMMAND_TYPE_CREATE_ALL_DATA_PATH_INTERFACES:
                    Log.wtf(TAG,
                            "processTimeout: CREATE_ALL_DATA_PATH_INTERFACES - shouldn't be "
                                    + "waiting!");
                    break;
                case COMMAND_TYPE_DELETE_ALL_DATA_PATH_INTERFACES:
                    Log.wtf(TAG,
                            "processTimeout: DELETE_ALL_DATA_PATH_INTERFACES - shouldn't be "
                                    + "waiting!");
                    break;
                case COMMAND_TYPE_CREATE_DATA_PATH_INTERFACE:
                    // TODO: fix status: timeout
                    onCreateDataPathInterfaceResponseLocal(mCurrentCommand, false, 0);
                    break;
                case COMMAND_TYPE_DELETE_DATA_PATH_INTERFACE:
                    // TODO: fix status: timeout
                    onDeleteDataPathInterfaceResponseLocal(mCurrentCommand, false, 0);
                    break;
                case COMMAND_TYPE_INITIATE_DATA_PATH_SETUP:
                    // TODO: fix status: timeout
                    onInitiateDataPathResponseFailLocal(mCurrentCommand, 0);
                    break;
                case COMMAND_TYPE_RESPOND_TO_DATA_PATH_SETUP_REQUEST:
                    // TODO: fix status: timeout
                    onRespondToDataPathSetupRequestResponseLocal(mCurrentCommand, false, 0);
                    break;
                case COMMAND_TYPE_END_DATA_PATH:
                    // TODO: fix status: timeout
                    onEndPathEndResponseLocal(mCurrentCommand, false, 0);
                    break;
                case COMMAND_TYPE_DELAYED_INITIALIZATION:
                    Log.wtf(TAG,
                            "processTimeout: COMMAND_TYPE_DELAYED_INITIALIZATION - shouldn't be "
                                    + "waiting!");
                    break;
                case COMMAND_TYPE_GET_AWARE:
                    Log.wtf(TAG,
                            "processTimeout: COMMAND_TYPE_GET_AWARE - shouldn't be waiting!");
                    break;
                case COMMAND_TYPE_RELEASE_AWARE:
                    Log.wtf(TAG,
                            "processTimeout: COMMAND_TYPE_RELEASE_AWARE - shouldn't be waiting!");
                    break;
                default:
                    Log.wtf(TAG, "processTimeout: this isn't a COMMAND -- msg=" + msg);
                    /* fall-through */
            }

            mCurrentCommand = null;
            mCurrentTransactionId = TRANSACTION_ID_IGNORE;
        }

        private void updateSendMessageTimeout() {
            if (VDBG) {
                Log.v(TAG, "updateSendMessageTimeout: mHostQueuedSendMessages.size()="
                        + mHostQueuedSendMessages.size() + ", mFwQueuedSendMessages.size()="
                        + mFwQueuedSendMessages.size() + ", mSendQueueBlocked="
                        + mSendQueueBlocked);
            }
            Iterator<Message> it = mFwQueuedSendMessages.values().iterator();
            if (it.hasNext()) {
                /*
                 * Schedule timeout based on the first message in the queue (which is the earliest
                 * submitted message). Timeout = queuing time + timeout constant.
                 */
                Message msg = it.next();
                mSendMessageTimeoutMessage.schedule(
                        msg.getData().getLong(MESSAGE_BUNDLE_KEY_SEND_MESSAGE_ENQUEUE_TIME)
                        + AWARE_SEND_MESSAGE_TIMEOUT);
            } else {
                mSendMessageTimeoutMessage.cancel();
            }
        }

        private void processSendMessageTimeout() {
            if (mDbg) {
                Log.v(TAG, "processSendMessageTimeout: mHostQueuedSendMessages.size()="
                        + mHostQueuedSendMessages.size() + ", mFwQueuedSendMessages.size()="
                        + mFwQueuedSendMessages.size() + ", mSendQueueBlocked="
                        + mSendQueueBlocked);

            }
            /*
             * Note: using 'first' to always time-out (remove) at least 1 notification (partially)
             * due to test code needs: there's no way to mock elapsedRealtime(). TODO: replace with
             * injected getClock() once moved off of mmwd.
             */
            boolean first = true;
            long currentTime = SystemClock.elapsedRealtime();
            Iterator<Map.Entry<Short, Message>> it = mFwQueuedSendMessages.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Short, Message> entry = it.next();
                short transactionId = entry.getKey();
                Message message = entry.getValue();
                long messageEnqueueTime = message.getData().getLong(
                        MESSAGE_BUNDLE_KEY_SEND_MESSAGE_ENQUEUE_TIME);
                if (first || messageEnqueueTime + AWARE_SEND_MESSAGE_TIMEOUT <= currentTime) {
                    if (mDbg) {
                        Log.v(TAG, "processSendMessageTimeout: expiring - transactionId="
                                + transactionId + ", message=" + message
                                + ", due to messageEnqueueTime=" + messageEnqueueTime
                                + ", currentTime=" + currentTime);
                    }
                    onMessageSendFailLocal(message, NanStatusType.INTERNAL_FAILURE);
                    it.remove();
                    first = false;
                } else {
                    break;
                }
            }
            updateSendMessageTimeout();
            mSendQueueBlocked = false;
            transmitNextMessage();
        }

        @Override
        protected String getLogRecString(Message msg) {
            StringBuilder sb = new StringBuilder(WifiAwareStateManager.messageToString(msg));

            if (msg.what == MESSAGE_TYPE_COMMAND
                    && mCurrentTransactionId != TRANSACTION_ID_IGNORE) {
                sb.append(" (Transaction ID=").append(mCurrentTransactionId).append(")");
            }

            return sb.toString();
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            pw.println("WifiAwareStateMachine:");
            pw.println("  mNextTransactionId: " + mNextTransactionId);
            pw.println("  mNextSessionId: " + mNextSessionId);
            pw.println("  mCurrentCommand: " + mCurrentCommand);
            pw.println("  mCurrentTransaction: " + mCurrentTransactionId);
            pw.println("  mSendQueueBlocked: " + mSendQueueBlocked);
            pw.println("  mSendArrivalSequenceCounter: " + mSendArrivalSequenceCounter);
            pw.println("  mHostQueuedSendMessages: [" + mHostQueuedSendMessages + "]");
            pw.println("  mFwQueuedSendMessages: [" + mFwQueuedSendMessages + "]");
            super.dump(fd, pw, args);
        }
    }

    private void sendAwareStateChangedBroadcast(boolean enabled) {
        if (VDBG) {
            Log.v(TAG, "sendAwareStateChangedBroadcast: enabled=" + enabled);
        }
        final Intent intent = new Intent(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    /*
     * COMMANDS
     */

    private boolean connectLocal(short transactionId, int clientId, int uid, int pid,
            String callingPackage, IWifiAwareEventCallback callback, ConfigRequest configRequest,
            boolean notifyIdentityChange) {
        if (VDBG) {
            Log.v(TAG, "connectLocal(): transactionId=" + transactionId + ", clientId=" + clientId
                    + ", uid=" + uid + ", pid=" + pid + ", callingPackage=" + callingPackage
                    + ", callback=" + callback + ", configRequest=" + configRequest
                    + ", notifyIdentityChange=" + notifyIdentityChange);
        }

        if (!mUsageEnabled) {
            Log.w(TAG, "connect(): called with mUsageEnabled=false");
            try {
                callback.onConnectFail(NanStatusType.INTERNAL_FAILURE);
                mAwareMetrics.recordAttachStatus(NanStatusType.INTERNAL_FAILURE);
            } catch (RemoteException e) {
                Log.w(TAG, "connectLocal onConnectFail(): RemoteException (FYI): " + e);
            }
            return false;
        }

        if (mClients.get(clientId) != null) {
            Log.e(TAG, "connectLocal: entry already exists for clientId=" + clientId);
        }

        if (VDBG) {
            Log.v(TAG, "mCurrentAwareConfiguration=" + mCurrentAwareConfiguration
                    + ", mCurrentIdentityNotification=" + mCurrentIdentityNotification);
        }

        ConfigRequest merged = mergeConfigRequests(configRequest);
        if (merged == null) {
            Log.e(TAG, "connectLocal: requested configRequest=" + configRequest
                    + ", incompatible with current configurations");
            try {
                callback.onConnectFail(NanStatusType.INTERNAL_FAILURE);
                mAwareMetrics.recordAttachStatus(NanStatusType.INTERNAL_FAILURE);
            } catch (RemoteException e) {
                Log.w(TAG, "connectLocal onConnectFail(): RemoteException (FYI): " + e);
            }
            return false;
        } else if (VDBG) {
            Log.v(TAG, "connectLocal: merged=" + merged);
        }

        if (mCurrentAwareConfiguration != null && mCurrentAwareConfiguration.equals(merged)
                && (mCurrentIdentityNotification || !notifyIdentityChange)) {
            try {
                callback.onConnectSuccess(clientId);
            } catch (RemoteException e) {
                Log.w(TAG, "connectLocal onConnectSuccess(): RemoteException (FYI): " + e);
            }
            WifiAwareClientState client = new WifiAwareClientState(mContext, clientId, uid, pid,
                    callingPackage, callback, configRequest, notifyIdentityChange,
                    SystemClock.elapsedRealtime());
            client.mDbg = mDbg;
            client.onInterfaceAddressChange(mCurrentDiscoveryInterfaceMac);
            mClients.append(clientId, client);
            mAwareMetrics.recordAttachSession(uid, notifyIdentityChange, mClients);
            return false;
        }
        boolean notificationRequired =
                doesAnyClientNeedIdentityChangeNotifications() || notifyIdentityChange;

        if (mCurrentAwareConfiguration == null) {
            mWifiAwareNativeManager.tryToGetAware();
        }

        boolean success = mWifiAwareNativeApi.enableAndConfigure(transactionId, merged,
                notificationRequired, mCurrentAwareConfiguration == null,
                mPowerManager.isInteractive(), mPowerManager.isDeviceIdleMode());
        if (!success) {
            try {
                callback.onConnectFail(NanStatusType.INTERNAL_FAILURE);
                mAwareMetrics.recordAttachStatus(NanStatusType.INTERNAL_FAILURE);
            } catch (RemoteException e) {
                Log.w(TAG, "connectLocal onConnectFail(): RemoteException (FYI):  " + e);
            }
        }

        return success;
    }

    private boolean disconnectLocal(short transactionId, int clientId) {
        if (VDBG) {
            Log.v(TAG,
                    "disconnectLocal(): transactionId=" + transactionId + ", clientId=" + clientId);
        }

        WifiAwareClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "disconnectLocal: no entry for clientId=" + clientId);
            return false;
        }
        mClients.delete(clientId);
        mAwareMetrics.recordAttachSessionDuration(client.getCreationTime());
        SparseArray<WifiAwareDiscoverySessionState> sessions = client.getSessions();
        for (int i = 0; i < sessions.size(); ++i) {
            mAwareMetrics.recordDiscoverySessionDuration(sessions.valueAt(i).getCreationTime(),
                    sessions.valueAt(i).isPublishSession());
        }
        client.destroy();

        if (mClients.size() == 0) {
            mCurrentAwareConfiguration = null;
            deleteAllDataPathInterfaces();
            return mWifiAwareNativeApi.disable(transactionId);
        }

        ConfigRequest merged = mergeConfigRequests(null);
        if (merged == null) {
            Log.wtf(TAG, "disconnectLocal: got an incompatible merge on remaining configs!?");
            return false;
        }
        boolean notificationReqs = doesAnyClientNeedIdentityChangeNotifications();
        if (merged.equals(mCurrentAwareConfiguration)
                && mCurrentIdentityNotification == notificationReqs) {
            return false;
        }

        return mWifiAwareNativeApi.enableAndConfigure(transactionId, merged, notificationReqs,
                false, mPowerManager.isInteractive(), mPowerManager.isDeviceIdleMode());
    }

    private boolean reconfigureLocal(short transactionId) {
        if (VDBG) Log.v(TAG, "reconfigureLocal(): transactionId=" + transactionId);

        if (mClients.size() == 0) {
            // no clients - Aware is not enabled, nothing to reconfigure
            return false;
        }

        boolean notificationReqs = doesAnyClientNeedIdentityChangeNotifications();

        return mWifiAwareNativeApi.enableAndConfigure(transactionId, mCurrentAwareConfiguration,
                notificationReqs, false, mPowerManager.isInteractive(),
                mPowerManager.isDeviceIdleMode());
    }

    private void terminateSessionLocal(int clientId, int sessionId) {
        if (VDBG) {
            Log.v(TAG,
                    "terminateSessionLocal(): clientId=" + clientId + ", sessionId=" + sessionId);
        }

        WifiAwareClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "terminateSession: no client exists for clientId=" + clientId);
            return;
        }

        WifiAwareDiscoverySessionState session = client.terminateSession(sessionId);
        if (session != null) {
            mAwareMetrics.recordDiscoverySessionDuration(session.getCreationTime(),
                    session.isPublishSession());
        }
    }

    private boolean publishLocal(short transactionId, int clientId, PublishConfig publishConfig,
            IWifiAwareDiscoverySessionCallback callback) {
        if (VDBG) {
            Log.v(TAG, "publishLocal(): transactionId=" + transactionId + ", clientId=" + clientId
                    + ", publishConfig=" + publishConfig + ", callback=" + callback);
        }

        WifiAwareClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "publishLocal: no client exists for clientId=" + clientId);
            return false;
        }

        boolean success = mWifiAwareNativeApi.publish(transactionId, (byte) 0, publishConfig);
        if (!success) {
            try {
                callback.onSessionConfigFail(NanStatusType.INTERNAL_FAILURE);
            } catch (RemoteException e) {
                Log.w(TAG, "publishLocal onSessionConfigFail(): RemoteException (FYI): " + e);
            }
            mAwareMetrics.recordDiscoveryStatus(client.getUid(), NanStatusType.INTERNAL_FAILURE,
                    true);
        }

        return success;
    }

    private boolean updatePublishLocal(short transactionId, int clientId, int sessionId,
            PublishConfig publishConfig) {
        if (VDBG) {
            Log.v(TAG, "updatePublishLocal(): transactionId=" + transactionId + ", clientId="
                    + clientId + ", sessionId=" + sessionId + ", publishConfig=" + publishConfig);
        }

        WifiAwareClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "updatePublishLocal: no client exists for clientId=" + clientId);
            return false;
        }

        WifiAwareDiscoverySessionState session = client.getSession(sessionId);
        if (session == null) {
            Log.e(TAG, "updatePublishLocal: no session exists for clientId=" + clientId
                    + ", sessionId=" + sessionId);
            return false;
        }

        boolean status = session.updatePublish(transactionId, publishConfig);
        if (!status) {
            mAwareMetrics.recordDiscoveryStatus(client.getUid(), NanStatusType.INTERNAL_FAILURE,
                    true);
        }
        return status;
    }

    private boolean subscribeLocal(short transactionId, int clientId,
            SubscribeConfig subscribeConfig, IWifiAwareDiscoverySessionCallback callback) {
        if (VDBG) {
            Log.v(TAG, "subscribeLocal(): transactionId=" + transactionId + ", clientId=" + clientId
                    + ", subscribeConfig=" + subscribeConfig + ", callback=" + callback);
        }

        WifiAwareClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "subscribeLocal: no client exists for clientId=" + clientId);
            return false;
        }

        boolean success = mWifiAwareNativeApi.subscribe(transactionId, (byte) 0, subscribeConfig);
        if (!success) {
            try {
                callback.onSessionConfigFail(NanStatusType.INTERNAL_FAILURE);
            } catch (RemoteException e) {
                Log.w(TAG, "subscribeLocal onSessionConfigFail(): RemoteException (FYI): " + e);
            }
            mAwareMetrics.recordDiscoveryStatus(client.getUid(), NanStatusType.INTERNAL_FAILURE,
                    false);
        }

        return success;
    }

    private boolean updateSubscribeLocal(short transactionId, int clientId, int sessionId,
            SubscribeConfig subscribeConfig) {
        if (VDBG) {
            Log.v(TAG,
                    "updateSubscribeLocal(): transactionId=" + transactionId + ", clientId="
                            + clientId + ", sessionId=" + sessionId + ", subscribeConfig="
                            + subscribeConfig);
        }

        WifiAwareClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "updateSubscribeLocal: no client exists for clientId=" + clientId);
            return false;
        }

        WifiAwareDiscoverySessionState session = client.getSession(sessionId);
        if (session == null) {
            Log.e(TAG, "updateSubscribeLocal: no session exists for clientId=" + clientId
                    + ", sessionId=" + sessionId);
            return false;
        }

        boolean status = session.updateSubscribe(transactionId, subscribeConfig);
        if (!status) {
            mAwareMetrics.recordDiscoveryStatus(client.getUid(), NanStatusType.INTERNAL_FAILURE,
                    false);
        }
        return status;
    }

    private boolean sendFollowonMessageLocal(short transactionId, int clientId, int sessionId,
            int peerId, byte[] message, int messageId) {
        if (VDBG) {
            Log.v(TAG,
                    "sendFollowonMessageLocal(): transactionId=" + transactionId + ", clientId="
                            + clientId + ", sessionId=" + sessionId + ", peerId=" + peerId
                            + ", messageId=" + messageId);
        }

        WifiAwareClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "sendFollowonMessageLocal: no client exists for clientId=" + clientId);
            return false;
        }

        WifiAwareDiscoverySessionState session = client.getSession(sessionId);
        if (session == null) {
            Log.e(TAG, "sendFollowonMessageLocal: no session exists for clientId=" + clientId
                    + ", sessionId=" + sessionId);
            return false;
        }

        return session.sendMessage(transactionId, peerId, message, messageId);
    }

    private void enableUsageLocal() {
        if (VDBG) Log.v(TAG, "enableUsageLocal: mUsageEnabled=" + mUsageEnabled);

        if (mCapabilities == null) {
            getAwareInterface();
            queryCapabilities();
            releaseAwareInterface();
        }

        if (mUsageEnabled) {
            return;
        }
        mUsageEnabled = true;
        sendAwareStateChangedBroadcast(true);

        mAwareMetrics.recordEnableUsage();
    }

    private boolean disableUsageLocal(short transactionId) {
        if (VDBG) {
            Log.v(TAG, "disableUsageLocal: transactionId=" + transactionId + ", mUsageEnabled="
                    + mUsageEnabled);
        }

        if (!mUsageEnabled) {
            return false;
        }

        onAwareDownLocal();

        mUsageEnabled = false;
        boolean callDispatched = mWifiAwareNativeApi.disable(transactionId);

        sendAwareStateChangedBroadcast(false);

        mAwareMetrics.recordDisableUsage();

        return callDispatched;
    }

    private boolean initiateDataPathSetupLocal(short transactionId,
            WifiAwareNetworkSpecifier networkSpecifier, int peerId, int channelRequestType,
            int channel, byte[] peer, String interfaceName, byte[] pmk, String passphrase,
            boolean isOutOfBand) {
        if (VDBG) {
            Log.v(TAG, "initiateDataPathSetupLocal(): transactionId=" + transactionId
                    + ", networkSpecifier=" + networkSpecifier + ", peerId=" + peerId
                    + ", channelRequestType=" + channelRequestType + ", channel=" + channel
                    + ", peer="
                    + String.valueOf(HexEncoding.encode(peer)) + ", interfaceName=" + interfaceName
                    + ", pmk=" + ((pmk == null) ? "" : "*") + ", passphrase=" + (
                    (passphrase == null) ? "" : "*") + ", isOutOfBand="
                    + isOutOfBand);
        }

        boolean success = mWifiAwareNativeApi.initiateDataPath(transactionId, peerId,
                channelRequestType, channel, peer, interfaceName, pmk, passphrase, isOutOfBand,
                mCapabilities);
        if (!success) {
            mDataPathMgr.onDataPathInitiateFail(networkSpecifier, NanStatusType.INTERNAL_FAILURE);
        }

        return success;
    }

    private boolean respondToDataPathRequestLocal(short transactionId, boolean accept,
            int ndpId, String interfaceName, byte[] pmk, String passphrase, boolean isOutOfBand) {
        if (VDBG) {
            Log.v(TAG,
                    "respondToDataPathRequestLocal(): transactionId=" + transactionId + ", accept="
                            + accept + ", ndpId=" + ndpId + ", interfaceName=" + interfaceName
                            + ", pmk=" + ((pmk == null) ? "" : "*") + ", passphrase="
                            + ((passphrase == null) ? "" : "*") + ", isOutOfBand="
                            + isOutOfBand);
        }
        boolean success = mWifiAwareNativeApi.respondToDataPathRequest(transactionId, accept, ndpId,
                interfaceName, pmk, passphrase, isOutOfBand, mCapabilities);
        if (!success) {
            mDataPathMgr.onRespondToDataPathRequest(ndpId, false, NanStatusType.INTERNAL_FAILURE);
        }
        return success;
    }

    private boolean endDataPathLocal(short transactionId, int ndpId) {
        if (VDBG) {
            Log.v(TAG,
                    "endDataPathLocal: transactionId=" + transactionId + ", ndpId=" + ndpId);
        }

        return mWifiAwareNativeApi.endDataPath(transactionId, ndpId);
    }

    /*
     * RESPONSES
     */

    private void onConfigCompletedLocal(Message completedCommand) {
        if (VDBG) {
            Log.v(TAG, "onConfigCompleted: completedCommand=" + completedCommand);
        }

        if (completedCommand.arg1 == COMMAND_TYPE_CONNECT) {
            Bundle data = completedCommand.getData();

            int clientId = completedCommand.arg2;
            IWifiAwareEventCallback callback = (IWifiAwareEventCallback) completedCommand.obj;
            ConfigRequest configRequest = (ConfigRequest) data
                    .getParcelable(MESSAGE_BUNDLE_KEY_CONFIG);
            int uid = data.getInt(MESSAGE_BUNDLE_KEY_UID);
            int pid = data.getInt(MESSAGE_BUNDLE_KEY_PID);
            boolean notifyIdentityChange = data.getBoolean(
                    MESSAGE_BUNDLE_KEY_NOTIFY_IDENTITY_CHANGE);
            String callingPackage = data.getString(MESSAGE_BUNDLE_KEY_CALLING_PACKAGE);

            WifiAwareClientState client = new WifiAwareClientState(mContext, clientId, uid, pid,
                    callingPackage, callback, configRequest, notifyIdentityChange,
                    SystemClock.elapsedRealtime());
            client.mDbg = mDbg;
            mClients.put(clientId, client);
            mAwareMetrics.recordAttachSession(uid, notifyIdentityChange, mClients);
            try {
                callback.onConnectSuccess(clientId);
            } catch (RemoteException e) {
                Log.w(TAG,
                        "onConfigCompletedLocal onConnectSuccess(): RemoteException (FYI): " + e);
            }
            client.onInterfaceAddressChange(mCurrentDiscoveryInterfaceMac);
        } else if (completedCommand.arg1 == COMMAND_TYPE_DISCONNECT) {
            /*
             * NOP (i.e. updated configuration after disconnecting a client)
             */
        } else if (completedCommand.arg1 == COMMAND_TYPE_RECONFIGURE) {
            /*
             * NOP (i.e. updated configuration at power saving event)
             */
        } else {
            Log.wtf(TAG, "onConfigCompletedLocal: unexpected completedCommand=" + completedCommand);
            return;
        }

        if (mCurrentAwareConfiguration == null) { // enabled (as opposed to re-configured)
            createAllDataPathInterfaces();
        }
        mCurrentAwareConfiguration = mergeConfigRequests(null);
        if (mCurrentAwareConfiguration == null) {
            Log.wtf(TAG, "onConfigCompletedLocal: got a null merged configuration after config!?");
        }
        mCurrentIdentityNotification = doesAnyClientNeedIdentityChangeNotifications();
    }

    private void onConfigFailedLocal(Message failedCommand, int reason) {
        if (VDBG) {
            Log.v(TAG,
                    "onConfigFailedLocal: failedCommand=" + failedCommand + ", reason=" + reason);
        }

        if (failedCommand.arg1 == COMMAND_TYPE_CONNECT) {
            IWifiAwareEventCallback callback = (IWifiAwareEventCallback) failedCommand.obj;

            try {
                callback.onConnectFail(reason);
                mAwareMetrics.recordAttachStatus(reason);
            } catch (RemoteException e) {
                Log.w(TAG, "onConfigFailedLocal onConnectFail(): RemoteException (FYI): " + e);
            }
        } else if (failedCommand.arg1 == COMMAND_TYPE_DISCONNECT) {
            /*
             * NOP (tried updating configuration after disconnecting a client -
             * shouldn't fail but there's nothing to do - the old configuration
             * is still up-and-running).
             *
             * OR: timed-out getting a response to a disable. Either way a NOP.
             */
        } else if (failedCommand.arg1 == COMMAND_TYPE_RECONFIGURE) {
            /*
             * NOP (configuration change as part of possibly power saving event - should not
             * fail but there's nothing to do).
             */
        } else {
            Log.wtf(TAG, "onConfigFailedLocal: unexpected failedCommand=" + failedCommand);
            return;
        }
    }

    private void onDisableResponseLocal(Message command, int reason) {
        if (VDBG) {
            Log.v(TAG, "onDisableResponseLocal: command=" + command + ", reason=" + reason);
        }

        /*
         * do nothing:
         * - success: was waiting so that don't enable while disabling
         * - fail: shouldn't happen (though can if already disabled for instance)
         */
        if (reason != NanStatusType.SUCCESS) {
            Log.e(TAG, "onDisableResponseLocal: FAILED!? command=" + command + ", reason="
                    + reason);
        }

        mAwareMetrics.recordDisableAware();
    }

    private void onSessionConfigSuccessLocal(Message completedCommand, byte pubSubId,
            boolean isPublish) {
        if (VDBG) {
            Log.v(TAG, "onSessionConfigSuccessLocal: completedCommand=" + completedCommand
                    + ", pubSubId=" + pubSubId + ", isPublish=" + isPublish);
        }

        if (completedCommand.arg1 == COMMAND_TYPE_PUBLISH
                || completedCommand.arg1 == COMMAND_TYPE_SUBSCRIBE) {
            int clientId = completedCommand.arg2;
            IWifiAwareDiscoverySessionCallback callback =
                    (IWifiAwareDiscoverySessionCallback) completedCommand.obj;

            WifiAwareClientState client = mClients.get(clientId);
            if (client == null) {
                Log.e(TAG,
                        "onSessionConfigSuccessLocal: no client exists for clientId=" + clientId);
                return;
            }

            int sessionId = mSm.mNextSessionId++;
            try {
                callback.onSessionStarted(sessionId);
            } catch (RemoteException e) {
                Log.e(TAG, "onSessionConfigSuccessLocal: onSessionStarted() RemoteException=" + e);
                return;
            }

            boolean isRangingEnabled = false;
            int minRange = -1;
            int maxRange = -1;
            if (completedCommand.arg1 == COMMAND_TYPE_PUBLISH) {
                PublishConfig publishConfig = completedCommand.getData().getParcelable(
                        MESSAGE_BUNDLE_KEY_CONFIG);
                isRangingEnabled = publishConfig.mEnableRanging;
            } else {
                SubscribeConfig subscribeConfig = completedCommand.getData().getParcelable(
                        MESSAGE_BUNDLE_KEY_CONFIG);
                isRangingEnabled =
                        subscribeConfig.mMinDistanceMmSet || subscribeConfig.mMaxDistanceMmSet;
                if (subscribeConfig.mMinDistanceMmSet) {
                    minRange = subscribeConfig.mMinDistanceMm;
                }
                if (subscribeConfig.mMaxDistanceMmSet) {
                    maxRange = subscribeConfig.mMaxDistanceMm;
                }
            }

            WifiAwareDiscoverySessionState session = new WifiAwareDiscoverySessionState(
                    mWifiAwareNativeApi, sessionId, pubSubId, callback, isPublish, isRangingEnabled,
                    SystemClock.elapsedRealtime());
            session.mDbg = mDbg;
            client.addSession(session);

            if (isRangingEnabled) {
                mAwareMetrics.recordDiscoverySessionWithRanging(client.getUid(),
                        completedCommand.arg1 != COMMAND_TYPE_PUBLISH, minRange, maxRange,
                        mClients);
            } else {
                mAwareMetrics.recordDiscoverySession(client.getUid(), mClients);
            }
            mAwareMetrics.recordDiscoveryStatus(client.getUid(), NanStatusType.SUCCESS,
                    completedCommand.arg1 == COMMAND_TYPE_PUBLISH);

        } else if (completedCommand.arg1 == COMMAND_TYPE_UPDATE_PUBLISH
                || completedCommand.arg1 == COMMAND_TYPE_UPDATE_SUBSCRIBE) {
            int clientId = completedCommand.arg2;
            int sessionId = completedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);

            WifiAwareClientState client = mClients.get(clientId);
            if (client == null) {
                Log.e(TAG,
                        "onSessionConfigSuccessLocal: no client exists for clientId=" + clientId);
                return;
            }

            WifiAwareDiscoverySessionState session = client.getSession(sessionId);
            if (session == null) {
                Log.e(TAG, "onSessionConfigSuccessLocal: no session exists for clientId=" + clientId
                        + ", sessionId=" + sessionId);
                return;
            }

            try {
                session.getCallback().onSessionConfigSuccess();
            } catch (RemoteException e) {
                Log.e(TAG, "onSessionConfigSuccessLocal: onSessionConfigSuccess() RemoteException="
                        + e);
            }
            mAwareMetrics.recordDiscoveryStatus(client.getUid(), NanStatusType.SUCCESS,
                    completedCommand.arg1 == COMMAND_TYPE_UPDATE_PUBLISH);
        } else {
            Log.wtf(TAG,
                    "onSessionConfigSuccessLocal: unexpected completedCommand=" + completedCommand);
        }
    }

    private void onSessionConfigFailLocal(Message failedCommand, boolean isPublish, int reason) {
        if (VDBG) {
            Log.v(TAG, "onSessionConfigFailLocal: failedCommand=" + failedCommand + ", isPublish="
                    + isPublish + ", reason=" + reason);
        }

        if (failedCommand.arg1 == COMMAND_TYPE_PUBLISH
                || failedCommand.arg1 == COMMAND_TYPE_SUBSCRIBE) {
            int clientId = failedCommand.arg2;
            IWifiAwareDiscoverySessionCallback callback =
                    (IWifiAwareDiscoverySessionCallback) failedCommand.obj;

            WifiAwareClientState client = mClients.get(clientId);
            if (client == null) {
                Log.e(TAG, "onSessionConfigFailLocal: no client exists for clientId=" + clientId);
                return;
            }

            try {
                callback.onSessionConfigFail(reason);
            } catch (RemoteException e) {
                Log.w(TAG, "onSessionConfigFailLocal onSessionConfigFail(): RemoteException (FYI): "
                        + e);
            }
            mAwareMetrics.recordDiscoveryStatus(client.getUid(), reason,
                    failedCommand.arg1 == COMMAND_TYPE_PUBLISH);
        } else if (failedCommand.arg1 == COMMAND_TYPE_UPDATE_PUBLISH
                || failedCommand.arg1 == COMMAND_TYPE_UPDATE_SUBSCRIBE) {
            int clientId = failedCommand.arg2;
            int sessionId = failedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);

            WifiAwareClientState client = mClients.get(clientId);
            if (client == null) {
                Log.e(TAG, "onSessionConfigFailLocal: no client exists for clientId=" + clientId);
                return;
            }

            WifiAwareDiscoverySessionState session = client.getSession(sessionId);
            if (session == null) {
                Log.e(TAG, "onSessionConfigFailLocal: no session exists for clientId=" + clientId
                        + ", sessionId=" + sessionId);
                return;
            }

            try {
                session.getCallback().onSessionConfigFail(reason);
            } catch (RemoteException e) {
                Log.e(TAG, "onSessionConfigFailLocal: onSessionConfigFail() RemoteException=" + e);
            }
            mAwareMetrics.recordDiscoveryStatus(client.getUid(), reason,
                    failedCommand.arg1 == COMMAND_TYPE_UPDATE_PUBLISH);

            if (reason == NanStatusType.INVALID_SESSION_ID) {
                client.removeSession(sessionId);
            }
        } else {
            Log.wtf(TAG, "onSessionConfigFailLocal: unexpected failedCommand=" + failedCommand);
        }
    }

    private void onMessageSendSuccessLocal(Message completedCommand) {
        if (VDBG) {
            Log.v(TAG, "onMessageSendSuccess: completedCommand=" + completedCommand);
        }

        int clientId = completedCommand.arg2;
        int sessionId = completedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);
        int messageId = completedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_MESSAGE_ID);

        WifiAwareClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "onMessageSendSuccessLocal: no client exists for clientId=" + clientId);
            return;
        }

        WifiAwareDiscoverySessionState session = client.getSession(sessionId);
        if (session == null) {
            Log.e(TAG, "onMessageSendSuccessLocal: no session exists for clientId=" + clientId
                    + ", sessionId=" + sessionId);
            return;
        }

        try {
            session.getCallback().onMessageSendSuccess(messageId);
        } catch (RemoteException e) {
            Log.w(TAG, "onMessageSendSuccessLocal: RemoteException (FYI): " + e);
        }
    }

    private void onMessageSendFailLocal(Message failedCommand, int reason) {
        if (VDBG) {
            Log.v(TAG, "onMessageSendFail: failedCommand=" + failedCommand + ", reason=" + reason);
        }

        int clientId = failedCommand.arg2;
        int sessionId = failedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_SESSION_ID);
        int messageId = failedCommand.getData().getInt(MESSAGE_BUNDLE_KEY_MESSAGE_ID);

        WifiAwareClientState client = mClients.get(clientId);
        if (client == null) {
            Log.e(TAG, "onMessageSendFailLocal: no client exists for clientId=" + clientId);
            return;
        }

        WifiAwareDiscoverySessionState session = client.getSession(sessionId);
        if (session == null) {
            Log.e(TAG, "onMessageSendFailLocal: no session exists for clientId=" + clientId
                    + ", sessionId=" + sessionId);
            return;
        }

        try {
            session.getCallback().onMessageSendFail(messageId, reason);
        } catch (RemoteException e) {
            Log.e(TAG, "onMessageSendFailLocal: onMessageSendFail RemoteException=" + e);
        }
    }

    private void onCapabilitiesUpdatedResponseLocal(Capabilities capabilities) {
        if (VDBG) {
            Log.v(TAG, "onCapabilitiesUpdatedResponseLocal: capabilites=" + capabilities);
        }

        mCapabilities = capabilities;
        mCharacteristics = null;
    }

    private void onCreateDataPathInterfaceResponseLocal(Message command, boolean success,
            int reasonOnFailure) {
        if (VDBG) {
            Log.v(TAG, "onCreateDataPathInterfaceResponseLocal: command=" + command + ", success="
                    + success + ", reasonOnFailure=" + reasonOnFailure);
        }

        if (success) {
            if (VDBG) {
                Log.v(TAG, "onCreateDataPathInterfaceResponseLocal: successfully created interface "
                        + command.obj);
            }
            mDataPathMgr.onInterfaceCreated((String) command.obj);
        } else {
            Log.e(TAG,
                    "onCreateDataPathInterfaceResponseLocal: failed when trying to create "
                            + "interface "
                            + command.obj + ". Reason code=" + reasonOnFailure);
        }
    }

    private void onDeleteDataPathInterfaceResponseLocal(Message command, boolean success,
            int reasonOnFailure) {
        if (VDBG) {
            Log.v(TAG, "onDeleteDataPathInterfaceResponseLocal: command=" + command + ", success="
                    + success + ", reasonOnFailure=" + reasonOnFailure);
        }

        if (success) {
            if (VDBG) {
                Log.v(TAG, "onDeleteDataPathInterfaceResponseLocal: successfully deleted interface "
                        + command.obj);
            }
            mDataPathMgr.onInterfaceDeleted((String) command.obj);
        } else {
            Log.e(TAG,
                    "onDeleteDataPathInterfaceResponseLocal: failed when trying to delete "
                            + "interface "
                            + command.obj + ". Reason code=" + reasonOnFailure);
        }
    }

    private void onInitiateDataPathResponseSuccessLocal(Message command, int ndpId) {
        if (VDBG) {
            Log.v(TAG, "onInitiateDataPathResponseSuccessLocal: command=" + command + ", ndpId="
                    + ndpId);
        }

        mDataPathMgr.onDataPathInitiateSuccess((WifiAwareNetworkSpecifier) command.obj, ndpId);
    }

    private void onInitiateDataPathResponseFailLocal(Message command, int reason) {
        if (VDBG) {
            Log.v(TAG, "onInitiateDataPathResponseFailLocal: command=" + command + ", reason="
                    + reason);
        }

        mDataPathMgr.onDataPathInitiateFail((WifiAwareNetworkSpecifier) command.obj, reason);
    }

    private void onRespondToDataPathSetupRequestResponseLocal(Message command, boolean success,
            int reasonOnFailure) {
        if (VDBG) {
            Log.v(TAG, "onRespondToDataPathSetupRequestResponseLocal: command=" + command
                    + ", success=" + success + ", reasonOnFailure=" + reasonOnFailure);
        }

        mDataPathMgr.onRespondToDataPathRequest(command.arg2, success, reasonOnFailure);
    }

    private void onEndPathEndResponseLocal(Message command, boolean success, int reasonOnFailure) {
        if (VDBG) {
            Log.v(TAG, "onEndPathEndResponseLocal: command=" + command
                    + ", success=" + success + ", reasonOnFailure=" + reasonOnFailure);
        }

        // TODO: do something with this
    }

    /*
     * NOTIFICATIONS
     */

    private void onInterfaceAddressChangeLocal(byte[] mac) {
        if (VDBG) {
            Log.v(TAG, "onInterfaceAddressChange: mac=" + String.valueOf(HexEncoding.encode(mac)));
        }

        mCurrentDiscoveryInterfaceMac = mac;

        for (int i = 0; i < mClients.size(); ++i) {
            WifiAwareClientState client = mClients.valueAt(i);
            client.onInterfaceAddressChange(mac);
        }

        mAwareMetrics.recordEnableAware();
    }

    private void onClusterChangeLocal(int flag, byte[] clusterId) {
        if (VDBG) {
            Log.v(TAG, "onClusterChange: flag=" + flag + ", clusterId="
                    + String.valueOf(HexEncoding.encode(clusterId)));
        }

        for (int i = 0; i < mClients.size(); ++i) {
            WifiAwareClientState client = mClients.valueAt(i);
            client.onClusterChange(flag, clusterId, mCurrentDiscoveryInterfaceMac);
        }

        mAwareMetrics.recordEnableAware();
    }

    private void onMatchLocal(int pubSubId, int requestorInstanceId, byte[] peerMac,
            byte[] serviceSpecificInfo, byte[] matchFilter, int rangingIndication, int rangeMm) {
        if (VDBG) {
            Log.v(TAG,
                    "onMatch: pubSubId=" + pubSubId + ", requestorInstanceId=" + requestorInstanceId
                            + ", peerDiscoveryMac=" + String.valueOf(HexEncoding.encode(peerMac))
                            + ", serviceSpecificInfo=" + Arrays.toString(serviceSpecificInfo)
                            + ", matchFilter=" + Arrays.toString(matchFilter)
                            + ", rangingIndication=" + rangingIndication + ", rangeMm=" + rangeMm);
        }

        Pair<WifiAwareClientState, WifiAwareDiscoverySessionState> data =
                getClientSessionForPubSubId(pubSubId);
        if (data == null) {
            Log.e(TAG, "onMatch: no session found for pubSubId=" + pubSubId);
            return;
        }

        if (data.second.isRangingEnabled()) {
            mAwareMetrics.recordMatchIndicationForRangeEnabledSubscribe(rangingIndication != 0);
        }
        data.second.onMatch(requestorInstanceId, peerMac, serviceSpecificInfo, matchFilter,
                rangingIndication, rangeMm);
    }

    private void onSessionTerminatedLocal(int pubSubId, boolean isPublish, int reason) {
        if (VDBG) {
            Log.v(TAG, "onSessionTerminatedLocal: pubSubId=" + pubSubId + ", isPublish=" + isPublish
                    + ", reason=" + reason);
        }

        Pair<WifiAwareClientState, WifiAwareDiscoverySessionState> data =
                getClientSessionForPubSubId(pubSubId);
        if (data == null) {
            Log.e(TAG, "onSessionTerminatedLocal: no session found for pubSubId=" + pubSubId);
            return;
        }

        try {
            data.second.getCallback().onSessionTerminated(reason);
        } catch (RemoteException e) {
            Log.w(TAG,
                    "onSessionTerminatedLocal onSessionTerminated(): RemoteException (FYI): " + e);
        }
        data.first.removeSession(data.second.getSessionId());
        mAwareMetrics.recordDiscoverySessionDuration(data.second.getCreationTime(),
                data.second.isPublishSession());
    }

    private void onMessageReceivedLocal(int pubSubId, int requestorInstanceId, byte[] peerMac,
            byte[] message) {
        if (VDBG) {
            Log.v(TAG,
                    "onMessageReceivedLocal: pubSubId=" + pubSubId + ", requestorInstanceId="
                            + requestorInstanceId + ", peerDiscoveryMac="
                            + String.valueOf(HexEncoding.encode(peerMac)));
        }

        Pair<WifiAwareClientState, WifiAwareDiscoverySessionState> data =
                getClientSessionForPubSubId(pubSubId);
        if (data == null) {
            Log.e(TAG, "onMessageReceivedLocal: no session found for pubSubId=" + pubSubId);
            return;
        }

        data.second.onMessageReceived(requestorInstanceId, peerMac, message);
    }

    private void onAwareDownLocal() {
        if (VDBG) {
            Log.v(TAG, "onAwareDown: mCurrentAwareConfiguration=" + mCurrentAwareConfiguration);
        }
        if (mCurrentAwareConfiguration == null) {
            return;
        }

        for (int i = 0; i < mClients.size(); ++i) {
            mAwareMetrics.recordAttachSessionDuration(mClients.valueAt(i).getCreationTime());
            SparseArray<WifiAwareDiscoverySessionState> sessions = mClients.valueAt(
                    i).getSessions();
            for (int j = 0; j < sessions.size(); ++j) {
                mAwareMetrics.recordDiscoverySessionDuration(sessions.valueAt(i).getCreationTime(),
                        sessions.valueAt(i).isPublishSession());
            }
        }
        mAwareMetrics.recordDisableAware();

        mClients.clear();
        mCurrentAwareConfiguration = null;
        mSm.onAwareDownCleanupSendQueueState();
        mDataPathMgr.onAwareDownCleanupDataPaths();
        mCurrentDiscoveryInterfaceMac = ALL_ZERO_MAC;
        deleteAllDataPathInterfaces();
    }

    /*
     * Utilities
     */

    private Pair<WifiAwareClientState, WifiAwareDiscoverySessionState> getClientSessionForPubSubId(
            int pubSubId) {
        for (int i = 0; i < mClients.size(); ++i) {
            WifiAwareClientState client = mClients.valueAt(i);
            WifiAwareDiscoverySessionState session = client.getAwareSessionStateForPubSubId(
                    pubSubId);
            if (session != null) {
                return new Pair<>(client, session);
            }
        }

        return null;
    }

    /**
     * Merge all the existing client configurations with the (optional) input configuration request.
     * If the configurations are "incompatible" (rules in comment below) return a null.
     */
    private ConfigRequest mergeConfigRequests(ConfigRequest configRequest) {
        if (VDBG) {
            Log.v(TAG, "mergeConfigRequests(): mClients=[" + mClients + "], configRequest="
                    + configRequest);
        }

        if (mClients.size() == 0 && configRequest == null) {
            Log.e(TAG, "mergeConfigRequests: invalid state - called with 0 clients registered!");
            return null;
        }

        // TODO: continue working on merge algorithm:
        // - if any request 5g: enable
        // - maximal master preference
        // - cluster range: must be identical
        // - if any request identity change: enable
        // - discovery window: minimum value if specified, 0 (disable) is considered an infinity
        boolean support5gBand = false;
        int masterPreference = 0;
        boolean clusterIdValid = false;
        int clusterLow = 0;
        int clusterHigh = ConfigRequest.CLUSTER_ID_MAX;
        int[] discoveryWindowInterval =
                {ConfigRequest.DW_INTERVAL_NOT_INIT, ConfigRequest.DW_INTERVAL_NOT_INIT};
        if (configRequest != null) {
            support5gBand = configRequest.mSupport5gBand;
            masterPreference = configRequest.mMasterPreference;
            clusterIdValid = true;
            clusterLow = configRequest.mClusterLow;
            clusterHigh = configRequest.mClusterHigh;
            discoveryWindowInterval = configRequest.mDiscoveryWindowInterval;
        }
        for (int i = 0; i < mClients.size(); ++i) {
            ConfigRequest cr = mClients.valueAt(i).getConfigRequest();

            // any request turns on 5G
            if (cr.mSupport5gBand) {
                support5gBand = true;
            }

            // maximal master preference
            masterPreference = Math.max(masterPreference, cr.mMasterPreference);

            // cluster range must be the same across all config requests
            if (!clusterIdValid) {
                clusterIdValid = true;
                clusterLow = cr.mClusterLow;
                clusterHigh = cr.mClusterHigh;
            } else {
                if (clusterLow != cr.mClusterLow) return null;
                if (clusterHigh != cr.mClusterHigh) return null;
            }

            for (int band = ConfigRequest.NAN_BAND_24GHZ; band <= ConfigRequest.NAN_BAND_5GHZ;
                    ++band) {
                if (discoveryWindowInterval[band] == ConfigRequest.DW_INTERVAL_NOT_INIT) {
                    discoveryWindowInterval[band] = cr.mDiscoveryWindowInterval[band];
                } else if (cr.mDiscoveryWindowInterval[band]
                        == ConfigRequest.DW_INTERVAL_NOT_INIT) {
                    // do nothing: keep my values
                } else if (discoveryWindowInterval[band] == ConfigRequest.DW_DISABLE) {
                    discoveryWindowInterval[band] = cr.mDiscoveryWindowInterval[band];
                } else if (cr.mDiscoveryWindowInterval[band] == ConfigRequest.DW_DISABLE) {
                    // do nothing: keep my values
                } else {
                    discoveryWindowInterval[band] = Math.min(discoveryWindowInterval[band],
                            cr.mDiscoveryWindowInterval[band]);
                }
            }
        }
        ConfigRequest.Builder builder = new ConfigRequest.Builder().setSupport5gBand(support5gBand)
                .setMasterPreference(masterPreference).setClusterLow(clusterLow)
                .setClusterHigh(clusterHigh);
        for (int band = ConfigRequest.NAN_BAND_24GHZ; band <= ConfigRequest.NAN_BAND_5GHZ; ++band) {
            if (discoveryWindowInterval[band] != ConfigRequest.DW_INTERVAL_NOT_INIT) {
                builder.setDiscoveryWindowInterval(band, discoveryWindowInterval[band]);
            }
        }
        return builder.build();
    }

    private boolean doesAnyClientNeedIdentityChangeNotifications() {
        for (int i = 0; i < mClients.size(); ++i) {
            if (mClients.valueAt(i).getNotifyIdentityChange()) {
                return true;
            }
        }
        return false;
    }

    private static String messageToString(Message msg) {
        StringBuilder sb = new StringBuilder();

        String s = sSmToString.get(msg.what);
        if (s == null) {
            s = "<unknown>";
        }
        sb.append(s).append("/");

        if (msg.what == MESSAGE_TYPE_NOTIFICATION || msg.what == MESSAGE_TYPE_COMMAND
                || msg.what == MESSAGE_TYPE_RESPONSE) {
            s = sSmToString.get(msg.arg1);
            if (s == null) {
                s = "<unknown>";
            }
            sb.append(s);
        }

        if (msg.what == MESSAGE_TYPE_RESPONSE || msg.what == MESSAGE_TYPE_RESPONSE_TIMEOUT) {
            sb.append(" (Transaction ID=").append(msg.arg2).append(")");
        }

        return sb.toString();
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("AwareStateManager:");
        pw.println("  mClients: [" + mClients + "]");
        pw.println("  mUsageEnabled: " + mUsageEnabled);
        pw.println("  mCapabilities: [" + mCapabilities + "]");
        pw.println("  mCurrentAwareConfiguration: " + mCurrentAwareConfiguration);
        pw.println("  mCurrentIdentityNotification: " + mCurrentIdentityNotification);
        for (int i = 0; i < mClients.size(); ++i) {
            mClients.valueAt(i).dump(fd, pw, args);
        }
        pw.println("  mSettableParameters: " + mSettableParameters);
        mSm.dump(fd, pw, args);
        mDataPathMgr.dump(fd, pw, args);
        mWifiAwareNativeApi.dump(fd, pw, args);
        pw.println("mAwareMetrics:");
        mAwareMetrics.dump(fd, pw, args);
    }
}
