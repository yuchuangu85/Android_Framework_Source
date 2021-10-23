/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.contexthub;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.hardware.SensorPrivacyManager;
import android.hardware.SensorPrivacyManagerInternal;
import android.hardware.contexthub.V1_0.AsyncEventType;
import android.hardware.contexthub.V1_0.ContextHub;
import android.hardware.contexthub.V1_0.ContextHubMsg;
import android.hardware.contexthub.V1_0.Result;
import android.hardware.contexthub.V1_0.TransactionResult;
import android.hardware.contexthub.V1_2.HubAppInfo;
import android.hardware.contexthub.V1_2.IContexthubCallback;
import android.hardware.location.ContextHubInfo;
import android.hardware.location.ContextHubMessage;
import android.hardware.location.ContextHubTransaction;
import android.hardware.location.IContextHubCallback;
import android.hardware.location.IContextHubClient;
import android.hardware.location.IContextHubClientCallback;
import android.hardware.location.IContextHubService;
import android.hardware.location.IContextHubTransactionCallback;
import android.hardware.location.NanoApp;
import android.hardware.location.NanoAppBinary;
import android.hardware.location.NanoAppFilter;
import android.hardware.location.NanoAppInstanceInfo;
import android.hardware.location.NanoAppMessage;
import android.hardware.location.NanoAppState;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.Pair;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;
import com.android.server.location.ContextHubServiceProto;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @hide
 */
public class ContextHubService extends IContextHubService.Stub {
    private static final String TAG = "ContextHubService";

    /*
     * Constants for the type of transaction that is defined by ContextHubService.
     * This is used to report the transaction callback to clients, and is different from
     * ContextHubTransaction.Type.
     */
    public static final int MSG_ENABLE_NANO_APP = 1;
    public static final int MSG_DISABLE_NANO_APP = 2;
    public static final int MSG_LOAD_NANO_APP = 3;
    public static final int MSG_UNLOAD_NANO_APP = 4;
    public static final int MSG_QUERY_NANO_APPS = 5;
    public static final int MSG_QUERY_MEMORY = 6;
    public static final int MSG_HUB_RESET = 7;

    private static final int OS_APP_INSTANCE = -1;

    /*
     * Local flag to enable debug logging.
     */
    private static final boolean DEBUG_LOG_ENABLED = false;

    private final Context mContext;

    private final Map<Integer, ContextHubInfo> mContextHubIdToInfoMap;
    private final List<String> mSupportedContextHubPerms;
    private final List<ContextHubInfo> mContextHubInfoList;
    private final RemoteCallbackList<IContextHubCallback> mCallbacksList =
            new RemoteCallbackList<>();

    // Proxy object to communicate with the Context Hub HAL
    private final IContextHubWrapper mContextHubWrapper;

    // The manager for transaction queue
    private final ContextHubTransactionManager mTransactionManager;

    // The manager for sending messages to/from clients
    private final ContextHubClientManager mClientManager;

    // The default client for old API clients
    private final Map<Integer, IContextHubClient> mDefaultClientMap;

    // The manager for the internal nanoapp state cache
    private final NanoAppStateManager mNanoAppStateManager = new NanoAppStateManager();

    // True if WiFi is available for the Context Hub
    private boolean mIsWifiAvailable = false;

    // Lock object for sendWifiSettingUpdate()
    private final Object mSendWifiSettingUpdateLock = new Object();

    private final SensorPrivacyManagerInternal mSensorPrivacyManagerInternal;

    /**
     * Class extending the callback to register with a Context Hub.
     */
    private class ContextHubServiceCallback extends IContexthubCallback.Stub {
        private final int mContextHubId;

        ContextHubServiceCallback(int contextHubId) {
            mContextHubId = contextHubId;
        }

        @Override
        public void handleClientMsg(ContextHubMsg message) {
            handleClientMessageCallback(mContextHubId, message,
                    Collections.emptyList() /* nanoappPermissions */,
                    Collections.emptyList() /* messagePermissions */);
        }

        @Override
        public void handleTxnResult(int transactionId, int result) {
            handleTransactionResultCallback(mContextHubId, transactionId, result);
        }

        @Override
        public void handleHubEvent(int eventType) {
            handleHubEventCallback(mContextHubId, eventType);
        }

        @Override
        public void handleAppAbort(long nanoAppId, int abortCode) {
            handleAppAbortCallback(mContextHubId, nanoAppId, abortCode);
        }

        @Override
        public void handleAppsInfo(
                ArrayList<android.hardware.contexthub.V1_0.HubAppInfo> nanoAppInfoList) {
            handleQueryAppsCallback(mContextHubId,
                    ContextHubServiceUtil.toHubAppInfo_1_2(nanoAppInfoList));
        }

        @Override
        public void handleClientMsg_1_2(android.hardware.contexthub.V1_2.ContextHubMsg message,
                ArrayList<String> messagePermissions) {
            handleClientMessageCallback(mContextHubId, message.msg_1_0, message.permissions,
                    messagePermissions);
        }

        @Override
        public void handleAppsInfo_1_2(ArrayList<HubAppInfo> nanoAppInfoList) {
            handleQueryAppsCallback(mContextHubId, nanoAppInfoList);
        }
    }

    public ContextHubService(Context context) {
        mContext = context;

        mContextHubWrapper = getContextHubWrapper();
        if (mContextHubWrapper == null) {
            mTransactionManager = null;
            mClientManager = null;
            mSensorPrivacyManagerInternal = null;
            mDefaultClientMap = Collections.emptyMap();
            mContextHubIdToInfoMap = Collections.emptyMap();
            mSupportedContextHubPerms = Collections.emptyList();
            mContextHubInfoList = Collections.emptyList();
            return;
        }

        Pair<List<ContextHub>, List<String>> hubInfo;
        try {
            hubInfo = mContextHubWrapper.getHubs();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException while getting Context Hub info", e);
            hubInfo = new Pair(Collections.emptyList(), Collections.emptyList());
        }

        mContextHubIdToInfoMap = Collections.unmodifiableMap(
                ContextHubServiceUtil.createContextHubInfoMap(hubInfo.first));
        mSupportedContextHubPerms = hubInfo.second;
        mContextHubInfoList = new ArrayList<>(mContextHubIdToInfoMap.values());
        mClientManager = new ContextHubClientManager(mContext, mContextHubWrapper);
        mTransactionManager = new ContextHubTransactionManager(
                mContextHubWrapper.getHub(), mClientManager, mNanoAppStateManager);
        mSensorPrivacyManagerInternal =
                LocalServices.getService(SensorPrivacyManagerInternal.class);

        HashMap<Integer, IContextHubClient> defaultClientMap = new HashMap<>();
        for (int contextHubId : mContextHubIdToInfoMap.keySet()) {
            ContextHubInfo contextHubInfo = mContextHubIdToInfoMap.get(contextHubId);
            IContextHubClient client = mClientManager.registerClient(
                    contextHubInfo, createDefaultClientCallback(contextHubId),
                    null /* attributionTag */, mTransactionManager, mContext.getPackageName());
            defaultClientMap.put(contextHubId, client);

            try {
                mContextHubWrapper.registerCallback(
                        contextHubId, new ContextHubServiceCallback(contextHubId));
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while registering service callback for hub (ID = "
                        + contextHubId + ")", e);
            }

            // Do a query to initialize the service cache list of nanoapps
            // TODO(b/69270990): Remove this when old API is deprecated
            queryNanoAppsInternal(contextHubId);
        }
        mDefaultClientMap = Collections.unmodifiableMap(defaultClientMap);

        if (mContextHubWrapper.supportsLocationSettingNotifications()) {
            sendLocationSettingUpdate();
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.LOCATION_MODE),
                    true /* notifyForDescendants */,
                    new ContentObserver(null /* handler */) {
                        @Override
                        public void onChange(boolean selfChange) {
                            sendLocationSettingUpdate();
                        }
                    }, UserHandle.USER_ALL);
        }

        if (mContextHubWrapper.supportsWifiSettingNotifications()) {
            sendWifiSettingUpdate(true /* forceUpdate */);

            BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())
                            || WifiManager.ACTION_WIFI_SCAN_AVAILABILITY_CHANGED.equals(
                                intent.getAction())) {
                        sendWifiSettingUpdate(false /* forceUpdate */);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.ACTION_WIFI_SCAN_AVAILABILITY_CHANGED);
            mContext.registerReceiver(wifiReceiver, filter);

            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.WIFI_SCAN_ALWAYS_AVAILABLE),
                    true /* notifyForDescendants */,
                    new ContentObserver(null /* handler */) {
                        @Override
                        public void onChange(boolean selfChange) {
                            sendWifiSettingUpdate(false /* forceUpdate */);
                        }
                    }, UserHandle.USER_ALL);
        }

        if (mContextHubWrapper.supportsAirplaneModeSettingNotifications()) {
            sendAirplaneModeSettingUpdate();
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.AIRPLANE_MODE_ON),
                    true /* notifyForDescendants */,
                    new ContentObserver(null /* handler */) {
                        @Override
                        public void onChange(boolean selfChange) {
                            sendAirplaneModeSettingUpdate();
                        }
                    }, UserHandle.USER_ALL);
        }

        if (mContextHubWrapper.supportsMicrophoneDisableSettingNotifications()) {
            sendMicrophoneDisableSettingUpdateForCurrentUser();

            mSensorPrivacyManagerInternal.addSensorPrivacyListenerForAllUsers(
                    SensorPrivacyManager.Sensors.MICROPHONE, (userId, enabled) -> {
                        if (userId == getCurrentUserId()) {
                            Log.d(TAG, "User: " + userId + "mic privacy: " + enabled);
                            sendMicrophoneDisableSettingUpdate(enabled);
                        }
                });

        }
    }

    /**
     * Creates a default client callback for old API clients.
     *
     * @param contextHubId the ID of the hub to attach this client to
     * @return the internal callback interface
     */
    private IContextHubClientCallback createDefaultClientCallback(int contextHubId) {
        return new IContextHubClientCallback.Stub() {
            @Override
            public void onMessageFromNanoApp(NanoAppMessage message) {
                int nanoAppHandle = mNanoAppStateManager.getNanoAppHandle(
                        contextHubId, message.getNanoAppId());

                onMessageReceiptOldApi(
                        message.getMessageType(), contextHubId, nanoAppHandle,
                        message.getMessageBody());
            }

            @Override
            public void onHubReset() {
                byte[] data = {TransactionResult.SUCCESS};
                onMessageReceiptOldApi(MSG_HUB_RESET, contextHubId, OS_APP_INSTANCE, data);
            }

            @Override
            public void onNanoAppAborted(long nanoAppId, int abortCode) {
            }

            @Override
            public void onNanoAppLoaded(long nanoAppId) {
            }

            @Override
            public void onNanoAppUnloaded(long nanoAppId) {
            }

            @Override
            public void onNanoAppEnabled(long nanoAppId) {
            }

            @Override
            public void onNanoAppDisabled(long nanoAppId) {
            }

            @Override
            public void onClientAuthorizationChanged(long nanoAppId, int authorization) {
            }
        };
    }

    /**
     * @return the IContextHubWrapper interface
     */
    private IContextHubWrapper getContextHubWrapper() {
        IContextHubWrapper wrapper = IContextHubWrapper.maybeConnectTo1_2();
        if (wrapper == null) {
            wrapper = IContextHubWrapper.maybeConnectTo1_1();
        }
        if (wrapper == null) {
            wrapper = IContextHubWrapper.maybeConnectTo1_0();
        }

        return wrapper;
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver result) {
        new ContextHubShellCommand(mContext, this).exec(this, in, out, err, args, callback, result);
    }

    @Override
    public int registerCallback(IContextHubCallback callback) throws RemoteException {
        checkPermissions();
        mCallbacksList.register(callback);

        Log.d(TAG, "Added callback, total callbacks " +
                mCallbacksList.getRegisteredCallbackCount());
        return 0;
    }

    @Override
    public int[] getContextHubHandles() throws RemoteException {
        checkPermissions();
        return ContextHubServiceUtil.createPrimitiveIntArray(mContextHubIdToInfoMap.keySet());
    }

    @Override
    public ContextHubInfo getContextHubInfo(int contextHubHandle) throws RemoteException {
        checkPermissions();
        if (!mContextHubIdToInfoMap.containsKey(contextHubHandle)) {
            Log.e(TAG, "Invalid Context Hub handle " + contextHubHandle + " in getContextHubInfo");
            return null;
        }

        return mContextHubIdToInfoMap.get(contextHubHandle);
    }

    /**
     * Returns a List of ContextHubInfo object describing the available hubs.
     *
     * @return the List of ContextHubInfo objects
     */
    @Override
    public List<ContextHubInfo> getContextHubs() throws RemoteException {
        checkPermissions();
        return mContextHubInfoList;
    }

    /**
     * Creates an internal load transaction callback to be used for old API clients
     *
     * @param contextHubId  the ID of the hub to load the binary
     * @param nanoAppBinary the binary to load
     * @return the callback interface
     */
    private IContextHubTransactionCallback createLoadTransactionCallback(
            int contextHubId, NanoAppBinary nanoAppBinary) {
        return new IContextHubTransactionCallback.Stub() {
            @Override
            public void onTransactionComplete(int result) {
                handleLoadResponseOldApi(contextHubId, result, nanoAppBinary);
            }

            @Override
            public void onQueryResponse(int result, List<NanoAppState> nanoAppStateList) {
            }
        };
    }

    /**
     * Creates an internal unload transaction callback to be used for old API clients
     *
     * @param contextHubId the ID of the hub to unload the nanoapp
     * @return the callback interface
     */
    private IContextHubTransactionCallback createUnloadTransactionCallback(int contextHubId) {
        return new IContextHubTransactionCallback.Stub() {
            @Override
            public void onTransactionComplete(int result) {
                handleUnloadResponseOldApi(contextHubId, result);
            }

            @Override
            public void onQueryResponse(int result, List<NanoAppState> nanoAppStateList) {
            }
        };
    }

    /**
     * Creates an internal query transaction callback to be used for old API clients
     *
     * @param contextHubId the ID of the hub to query
     * @return the callback interface
     */
    private IContextHubTransactionCallback createQueryTransactionCallback(int contextHubId) {
        return new IContextHubTransactionCallback.Stub() {
            @Override
            public void onTransactionComplete(int result) {
            }

            @Override
            public void onQueryResponse(int result, List<NanoAppState> nanoAppStateList) {
                byte[] data = {(byte) result};
                onMessageReceiptOldApi(MSG_QUERY_NANO_APPS, contextHubId, OS_APP_INSTANCE, data);
            }
        };
    }

    @Override
    public int loadNanoApp(int contextHubHandle, NanoApp nanoApp) throws RemoteException {
        checkPermissions();
        if (mContextHubWrapper == null) {
            return -1;
        }
        if (!isValidContextHubId(contextHubHandle)) {
            Log.e(TAG, "Invalid Context Hub handle " + contextHubHandle + " in loadNanoApp");
            return -1;
        }
        if (nanoApp == null) {
            Log.e(TAG, "NanoApp cannot be null in loadNanoApp");
            return -1;
        }

        // Create an internal IContextHubTransactionCallback for the old API clients
        NanoAppBinary nanoAppBinary = new NanoAppBinary(nanoApp.getAppBinary());
        IContextHubTransactionCallback onCompleteCallback =
                createLoadTransactionCallback(contextHubHandle, nanoAppBinary);

        ContextHubServiceTransaction transaction = mTransactionManager.createLoadTransaction(
                contextHubHandle, nanoAppBinary, onCompleteCallback, getCallingPackageName());

        mTransactionManager.addTransaction(transaction);
        return 0;
    }

    @Override
    public int unloadNanoApp(int nanoAppHandle) throws RemoteException {
        checkPermissions();
        if (mContextHubWrapper == null) {
            return -1;
        }

        NanoAppInstanceInfo info =
                mNanoAppStateManager.getNanoAppInstanceInfo(nanoAppHandle);
        if (info == null) {
            Log.e(TAG, "Invalid nanoapp handle " + nanoAppHandle + " in unloadNanoApp");
            return -1;
        }

        int contextHubId = info.getContexthubId();
        long nanoAppId = info.getAppId();
        IContextHubTransactionCallback onCompleteCallback =
                createUnloadTransactionCallback(contextHubId);
        ContextHubServiceTransaction transaction = mTransactionManager.createUnloadTransaction(
                contextHubId, nanoAppId, onCompleteCallback, getCallingPackageName());

        mTransactionManager.addTransaction(transaction);
        return 0;
    }

    @Override
    public NanoAppInstanceInfo getNanoAppInstanceInfo(int nanoAppHandle) throws RemoteException {
        checkPermissions();

        return mNanoAppStateManager.getNanoAppInstanceInfo(nanoAppHandle);
    }

    @Override
    public int[] findNanoAppOnHub(
            int contextHubHandle, NanoAppFilter filter) throws RemoteException {
        checkPermissions();

        ArrayList<Integer> foundInstances = new ArrayList<>();
        if (filter != null) {
            mNanoAppStateManager.foreachNanoAppInstanceInfo((info) -> {
                if (filter.testMatch(info)) {
                    foundInstances.add(info.getHandle());
                }
            });
        }

        int[] retArray = new int[foundInstances.size()];
        for (int i = 0; i < foundInstances.size(); i++) {
            retArray[i] = foundInstances.get(i).intValue();
        }
        return retArray;
    }

    /**
     * Performs a query at the specified hub.
     *
     * This method should only be invoked internally by the service, either to update the service
     * cache or as a result of an explicit query requested by a client through the sendMessage API.
     *
     * @param contextHubId the ID of the hub to do the query
     * @return the result of the query
     * @throws IllegalStateException if the transaction queue is full
     */
    private int queryNanoAppsInternal(int contextHubId) {
        if (mContextHubWrapper == null) {
            return Result.UNKNOWN_FAILURE;
        }

        IContextHubTransactionCallback onCompleteCallback =
                createQueryTransactionCallback(contextHubId);
        ContextHubServiceTransaction transaction = mTransactionManager.createQueryTransaction(
                contextHubId, onCompleteCallback, getCallingPackageName());

        mTransactionManager.addTransaction(transaction);
        return Result.OK;
    }

    @Override
    public int sendMessage(int contextHubHandle, int nanoAppHandle, ContextHubMessage msg)
            throws RemoteException {
        checkPermissions();
        if (mContextHubWrapper == null) {
            return -1;
        }
        if (msg == null) {
            Log.e(TAG, "ContextHubMessage cannot be null in sendMessage");
            return -1;
        }
        if (msg.getData() == null) {
            Log.e(TAG, "ContextHubMessage message body cannot be null in sendMessage");
            return -1;
        }
        if (!isValidContextHubId(contextHubHandle)) {
            Log.e(TAG, "Invalid Context Hub handle " + contextHubHandle + " in sendMessage");
            return -1;
        }

        boolean success = false;
        if (nanoAppHandle == OS_APP_INSTANCE) {
            if (msg.getMsgType() == MSG_QUERY_NANO_APPS) {
                success = (queryNanoAppsInternal(contextHubHandle) == Result.OK);
            } else {
                Log.e(TAG, "Invalid OS message params of type " + msg.getMsgType());
            }
        } else {
            NanoAppInstanceInfo info = getNanoAppInstanceInfo(nanoAppHandle);
            if (info != null) {
                NanoAppMessage message = NanoAppMessage.createMessageToNanoApp(
                        info.getAppId(), msg.getMsgType(), msg.getData());

                IContextHubClient client = mDefaultClientMap.get(contextHubHandle);
                success = (client.sendMessageToNanoApp(message) ==
                        ContextHubTransaction.RESULT_SUCCESS);
            } else {
                Log.e(TAG, "Failed to send nanoapp message - nanoapp with handle "
                        + nanoAppHandle + " does not exist.");
            }
        }

        return success ? 0 : -1;
    }

    /**
     * Handles a unicast or broadcast message from a nanoapp.
     *
     * @param contextHubId   the ID of the hub the message came from
     * @param message        the message contents
     * @param reqPermissions the permissions required to consume this message
     */
    private void handleClientMessageCallback(
            int contextHubId, ContextHubMsg message, List<String> nanoappPermissions,
            List<String> messagePermissions) {
        mClientManager.onMessageFromNanoApp(
                contextHubId, message, nanoappPermissions, messagePermissions);
    }

    /**
     * A helper function to handle a load response from the Context Hub for the old API.
     * TODO(b/69270990): Remove this once the old APIs are obsolete.
     */
    private void handleLoadResponseOldApi(
            int contextHubId, int result, NanoAppBinary nanoAppBinary) {
        if (nanoAppBinary == null) {
            Log.e(TAG, "Nanoapp binary field was null for a load transaction");
            return;
        }

        byte[] data = new byte[5];
        data[0] = (byte) result;
        int nanoAppHandle = mNanoAppStateManager.getNanoAppHandle(
                contextHubId, nanoAppBinary.getNanoAppId());
        ByteBuffer.wrap(data, 1, 4).order(ByteOrder.nativeOrder()).putInt(nanoAppHandle);

        onMessageReceiptOldApi(MSG_LOAD_NANO_APP, contextHubId, OS_APP_INSTANCE, data);
    }

    /**
     * A helper function to handle an unload response from the Context Hub for the old API.
     *
     * TODO(b/69270990): Remove this once the old APIs are obsolete.
     */
    private void handleUnloadResponseOldApi(int contextHubId, int result) {
        byte[] data = new byte[1];
        data[0] = (byte) result;
        onMessageReceiptOldApi(MSG_UNLOAD_NANO_APP, contextHubId, OS_APP_INSTANCE, data);
    }

    /**
     * Handles a transaction response from a Context Hub.
     *
     * @param contextHubId  the ID of the hub the response came from
     * @param transactionId the ID of the transaction
     * @param result        the result of the transaction reported by the hub
     */
    private void handleTransactionResultCallback(int contextHubId, int transactionId, int result) {
        mTransactionManager.onTransactionResponse(transactionId, result);
    }

    /**
     * Handles an asynchronous event from a Context Hub.
     *
     * @param contextHubId the ID of the hub the response came from
     * @param eventType    the type of the event as defined in Context Hub HAL AsyncEventType
     */
    private void handleHubEventCallback(int contextHubId, int eventType) {
        if (eventType == AsyncEventType.RESTARTED) {
            sendLocationSettingUpdate();
            sendWifiSettingUpdate(true /* forceUpdate */);
            sendAirplaneModeSettingUpdate();
            sendMicrophoneDisableSettingUpdateForCurrentUser();

            mTransactionManager.onHubReset();
            queryNanoAppsInternal(contextHubId);

            mClientManager.onHubReset(contextHubId);
        } else {
            Log.i(TAG, "Received unknown hub event (hub ID = " + contextHubId + ", type = "
                    + eventType + ")");
        }
    }

    /**
     * Handles an asynchronous abort event of a nanoapp.
     *
     * @param contextHubId the ID of the hub that the nanoapp aborted in
     * @param nanoAppId    the ID of the aborted nanoapp
     * @param abortCode    the nanoapp-specific abort code
     */
    private void handleAppAbortCallback(int contextHubId, long nanoAppId, int abortCode) {
        mClientManager.onNanoAppAborted(contextHubId, nanoAppId, abortCode);
    }

    /**
     * Handles a query response from a Context Hub.
     *
     * @param contextHubId    the ID of the hub of the response
     * @param nanoAppInfoList the list of loaded nanoapps
     */
    private void handleQueryAppsCallback(int contextHubId, List<HubAppInfo> nanoAppInfoList) {
        List<NanoAppState> nanoAppStateList =
                ContextHubServiceUtil.createNanoAppStateList(nanoAppInfoList);

        mNanoAppStateManager.updateCache(contextHubId, nanoAppInfoList);
        mTransactionManager.onQueryResponse(nanoAppStateList);
    }

    /**
     * @param contextHubId the hub ID to validate
     * @return {@code true} if the ID represents that of an available hub, {@code false} otherwise
     */
    private boolean isValidContextHubId(int contextHubId) {
        return mContextHubIdToInfoMap.containsKey(contextHubId);
    }

    /**
     * Creates and registers a client at the service for the specified Context Hub.
     *
     * @param contextHubId   the ID of the hub this client is attached to
     * @param clientCallback the client interface to register with the service
     * @param attributionTag an optional attribution tag within the given package
     * @param packageName    the name of the package creating this client
     * @return the generated client interface, null if registration was unsuccessful
     * @throws IllegalArgumentException if contextHubId is not a valid ID
     * @throws IllegalStateException    if max number of clients have already registered
     * @throws NullPointerException     if clientCallback is null
     */
    @Override
    public IContextHubClient createClient(
            int contextHubId, IContextHubClientCallback clientCallback,
            @Nullable String attributionTag, String packageName) throws RemoteException {
        checkPermissions();
        if (!isValidContextHubId(contextHubId)) {
            throw new IllegalArgumentException("Invalid context hub ID " + contextHubId);
        }
        if (clientCallback == null) {
            throw new NullPointerException("Cannot register client with null callback");
        }

        ContextHubInfo contextHubInfo = mContextHubIdToInfoMap.get(contextHubId);
        return mClientManager.registerClient(
                contextHubInfo, clientCallback, attributionTag, mTransactionManager, packageName);
    }

    /**
     * Creates and registers a PendingIntent client at the service for the specified Context Hub.
     *
     * @param contextHubId  the ID of the hub this client is attached to
     * @param pendingIntent the PendingIntent associated with this client
     * @param nanoAppId     the ID of the nanoapp PendingIntent events will be sent for
     * @param attributionTag an optional attribution tag within the given package
     * @return the generated client interface
     * @throws IllegalArgumentException if hubInfo does not represent a valid hub
     * @throws IllegalStateException    if there were too many registered clients at the service
     */
    @Override
    public IContextHubClient createPendingIntentClient(
            int contextHubId, PendingIntent pendingIntent, long nanoAppId,
            @Nullable String attributionTag) throws RemoteException {
        checkPermissions();
        if (!isValidContextHubId(contextHubId)) {
            throw new IllegalArgumentException("Invalid context hub ID " + contextHubId);
        }

        ContextHubInfo contextHubInfo = mContextHubIdToInfoMap.get(contextHubId);
        return mClientManager.registerClient(
                contextHubInfo, pendingIntent, nanoAppId, attributionTag, mTransactionManager);
    }

    /**
     * Loads a nanoapp binary at the specified Context hub.
     *
     * @param contextHubId        the ID of the hub to load the binary
     * @param transactionCallback the client-facing transaction callback interface
     * @param nanoAppBinary       the binary to load
     * @throws IllegalStateException if the transaction queue is full
     */
    @Override
    public void loadNanoAppOnHub(
            int contextHubId, IContextHubTransactionCallback transactionCallback,
            NanoAppBinary nanoAppBinary) throws RemoteException {
        checkPermissions();
        if (!checkHalProxyAndContextHubId(
                contextHubId, transactionCallback, ContextHubTransaction.TYPE_LOAD_NANOAPP)) {
            return;
        }
        if (nanoAppBinary == null) {
            Log.e(TAG, "NanoAppBinary cannot be null in loadNanoAppOnHub");
            transactionCallback.onTransactionComplete(
                    ContextHubTransaction.RESULT_FAILED_BAD_PARAMS);
            return;
        }

        ContextHubServiceTransaction transaction = mTransactionManager.createLoadTransaction(
                contextHubId, nanoAppBinary, transactionCallback, getCallingPackageName());
        mTransactionManager.addTransaction(transaction);
    }

    /**
     * Unloads a nanoapp from the specified Context Hub.
     *
     * @param contextHubId        the ID of the hub to unload the nanoapp
     * @param transactionCallback the client-facing transaction callback interface
     * @param nanoAppId           the ID of the nanoapp to unload
     * @throws IllegalStateException if the transaction queue is full
     */
    @Override
    public void unloadNanoAppFromHub(
            int contextHubId, IContextHubTransactionCallback transactionCallback, long nanoAppId)
            throws RemoteException {
        checkPermissions();
        if (!checkHalProxyAndContextHubId(
                contextHubId, transactionCallback, ContextHubTransaction.TYPE_UNLOAD_NANOAPP)) {
            return;
        }

        ContextHubServiceTransaction transaction = mTransactionManager.createUnloadTransaction(
                contextHubId, nanoAppId, transactionCallback, getCallingPackageName());
        mTransactionManager.addTransaction(transaction);
    }

    /**
     * Enables a nanoapp at the specified Context Hub.
     *
     * @param contextHubId        the ID of the hub to enable the nanoapp
     * @param transactionCallback the client-facing transaction callback interface
     * @param nanoAppId           the ID of the nanoapp to enable
     * @throws IllegalStateException if the transaction queue is full
     */
    @Override
    public void enableNanoApp(
            int contextHubId, IContextHubTransactionCallback transactionCallback, long nanoAppId)
            throws RemoteException {
        checkPermissions();
        if (!checkHalProxyAndContextHubId(
                contextHubId, transactionCallback, ContextHubTransaction.TYPE_ENABLE_NANOAPP)) {
            return;
        }

        ContextHubServiceTransaction transaction = mTransactionManager.createEnableTransaction(
                contextHubId, nanoAppId, transactionCallback, getCallingPackageName());
        mTransactionManager.addTransaction(transaction);
    }

    /**
     * Disables a nanoapp at the specified Context Hub.
     *
     * @param contextHubId        the ID of the hub to disable the nanoapp
     * @param transactionCallback the client-facing transaction callback interface
     * @param nanoAppId           the ID of the nanoapp to disable
     * @throws IllegalStateException if the transaction queue is full
     */
    @Override
    public void disableNanoApp(
            int contextHubId, IContextHubTransactionCallback transactionCallback, long nanoAppId)
            throws RemoteException {
        checkPermissions();
        if (!checkHalProxyAndContextHubId(
                contextHubId, transactionCallback, ContextHubTransaction.TYPE_DISABLE_NANOAPP)) {
            return;
        }

        ContextHubServiceTransaction transaction = mTransactionManager.createDisableTransaction(
                contextHubId, nanoAppId, transactionCallback, getCallingPackageName());
        mTransactionManager.addTransaction(transaction);
    }

    /**
     * Queries for a list of nanoapps from the specified Context hub.
     *
     * @param contextHubId        the ID of the hub to query
     * @param transactionCallback the client-facing transaction callback interface
     * @throws IllegalStateException if the transaction queue is full
     */
    @Override
    public void queryNanoApps(int contextHubId, IContextHubTransactionCallback transactionCallback)
            throws RemoteException {
        checkPermissions();
        if (!checkHalProxyAndContextHubId(
                contextHubId, transactionCallback, ContextHubTransaction.TYPE_QUERY_NANOAPPS)) {
            return;
        }

        ContextHubServiceTransaction transaction = mTransactionManager.createQueryTransaction(
                contextHubId, transactionCallback, getCallingPackageName());
        mTransactionManager.addTransaction(transaction);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        for (String arg : args) {
            if ("--proto".equals(arg)) {
                dump(new ProtoOutputStream(fd));
                return;
            }
        }

        pw.println("Dumping ContextHub Service");

        pw.println("");
        // dump ContextHubInfo
        pw.println("=================== CONTEXT HUBS ====================");
        for (ContextHubInfo hubInfo : mContextHubIdToInfoMap.values()) {
            pw.println(hubInfo);
        }
        pw.println("Supported permissions: "
                + Arrays.toString(mSupportedContextHubPerms.toArray()));
        pw.println("");
        pw.println("=================== NANOAPPS ====================");
        // Dump nanoAppHash
        mNanoAppStateManager.foreachNanoAppInstanceInfo((info) -> pw.println(info));

        pw.println("");
        pw.println("=================== CLIENTS ====================");
        pw.println(mClientManager);

        pw.println("");
        pw.println("=================== TRANSACTIONS ====================");
        pw.println(mTransactionManager);

        // dump eventLog
    }

    /* package */ void denyClientAuthState(int contextHubId, String packageName, long nanoAppId) {
        mClientManager.forEachClientOfHub(contextHubId, client -> {
            if (client.getPackageName().equals(packageName)) {
                client.updateNanoAppAuthState(
                        nanoAppId, Collections.emptyList() /* nanoappPermissions */,
                        false /* gracePeriodExpired */, true /* forceDenied */);
            }
        });
    }

    private void dump(ProtoOutputStream proto) {
        mContextHubIdToInfoMap.values().forEach(hubInfo -> {
            long token = proto.start(ContextHubServiceProto.CONTEXT_HUB_INFO);
            hubInfo.dump(proto);
            proto.end(token);
        });

        long token = proto.start(ContextHubServiceProto.CLIENT_MANAGER);
        mClientManager.dump(proto);
        proto.end(token);

        proto.flush();
    }

    private void checkPermissions() {
        ContextHubServiceUtil.checkPermissions(mContext);
    }

    private int onMessageReceiptOldApi(
            int msgType, int contextHubHandle, int appInstance, byte[] data) {
        if (data == null) {
            return -1;
        }

        int msgVersion = 0;
        int callbacksCount = mCallbacksList.beginBroadcast();
        if (DEBUG_LOG_ENABLED) {
            Log.v(TAG, "Sending message " + msgType + " version " + msgVersion + " from hubHandle "
                    + contextHubHandle + ", appInstance " + appInstance + ", callBackCount "
                    + callbacksCount);
        }

        if (callbacksCount < 1) {
            if (DEBUG_LOG_ENABLED) {
                Log.v(TAG, "No message callbacks registered.");
            }
            return 0;
        }

        ContextHubMessage msg = new ContextHubMessage(msgType, msgVersion, data);
        for (int i = 0; i < callbacksCount; ++i) {
            IContextHubCallback callback = mCallbacksList.getBroadcastItem(i);
            try {
                callback.onMessageReceipt(contextHubHandle, appInstance, msg);
            } catch (RemoteException e) {
                Log.i(TAG, "Exception (" + e + ") calling remote callback (" + callback + ").");
                continue;
            }
        }
        mCallbacksList.finishBroadcast();
        return 0;
    }

    /**
     * Validates the HAL proxy state and context hub ID to see if we can start the transaction.
     *
     * @param contextHubId    the ID of the hub to start the transaction
     * @param callback        the client transaction callback interface
     * @param transactionType the type of the transaction
     * @return {@code true} if mContextHubWrapper and contextHubId is valid, {@code false} otherwise
     */
    private boolean checkHalProxyAndContextHubId(
            int contextHubId, IContextHubTransactionCallback callback,
            @ContextHubTransaction.Type int transactionType) {
        if (mContextHubWrapper == null) {
            try {
                callback.onTransactionComplete(
                        ContextHubTransaction.RESULT_FAILED_HAL_UNAVAILABLE);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onTransactionComplete", e);
            }
            return false;
        }
        if (!isValidContextHubId(contextHubId)) {
            Log.e(TAG, "Cannot start "
                    + ContextHubTransaction.typeToString(transactionType, false /* upperCase */)
                    + " transaction for invalid hub ID " + contextHubId);
            try {
                callback.onTransactionComplete(ContextHubTransaction.RESULT_FAILED_BAD_PARAMS);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException while calling onTransactionComplete", e);
            }
            return false;
        }

        return true;
    }

    /**
     * Obtains the latest location setting value and notifies the Context Hub.
     */
    private void sendLocationSettingUpdate() {
        boolean enabled = mContext.getSystemService(LocationManager.class)
                .isLocationEnabledForUser(UserHandle.CURRENT);
        mContextHubWrapper.onLocationSettingChanged(enabled);
    }

    /**
     * Obtains the latest WiFi availability setting value and notifies the Context Hub.
     *
     * @param forceUpdate True to force send update to the Context Hub, otherwise only send the
     *                    update when the WiFi availability changes.
     */
    private void sendWifiSettingUpdate(boolean forceUpdate) {
        synchronized (mSendWifiSettingUpdateLock) {
            WifiManager wifiManager = mContext.getSystemService(WifiManager.class);
            boolean enabled = wifiManager.isWifiEnabled() || wifiManager.isScanAlwaysAvailable();
            if (forceUpdate || mIsWifiAvailable != enabled) {
                mIsWifiAvailable = enabled;
                mContextHubWrapper.onWifiSettingChanged(enabled);
            }
        }
    }

    /**
     * Obtains the latest airplane mode setting value and notifies the Context Hub.
     */
    private void sendAirplaneModeSettingUpdate() {
        boolean enabled =
                (Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.AIRPLANE_MODE_ON, 0)
                        == 1);
        mContextHubWrapper.onAirplaneModeSettingChanged(enabled);
    }

    /**
     * Notifies a microphone disable settings change to the Context Hub.
     */
    private void sendMicrophoneDisableSettingUpdate(boolean enabled) {
        Log.d(TAG, "Mic Disabled Setting: " + enabled);
        mContextHubWrapper.onMicrophoneDisableSettingChanged(enabled);
    }

    /**
     * Obtains the latest microphone disabled setting for the current user
     * and notifies the Context Hub.
     */
    private void sendMicrophoneDisableSettingUpdateForCurrentUser() {
        boolean isEnabled = mSensorPrivacyManagerInternal.isSensorPrivacyEnabled(
                getCurrentUserId(), SensorPrivacyManager.Sensors.MICROPHONE);
        sendMicrophoneDisableSettingUpdate(isEnabled);
    }


    private String getCallingPackageName() {
        return mContext.getPackageManager().getNameForUid(Binder.getCallingUid());
    }

    private int getCurrentUserId() {
        final long id = Binder.clearCallingIdentity();
        try {
            UserInfo currentUser = ActivityManager.getService().getCurrentUser();
            return currentUser.id;
        } catch (RemoteException e) {
            // Activity manager not running, nothing we can do - assume user 0.
        } finally {
            Binder.restoreCallingIdentity(id);
        }
        return UserHandle.USER_SYSTEM;
    }

    /**
     * Send a microphone disable settings update whenever the foreground user changes.
     * We always send a settings update regardless of the previous state for the same user
     * since the CHRE framework is expected to handle repeated identical setting update.
     */
    public void onUserChanged() {
        Log.d(TAG, "User changed to id: " + getCurrentUserId());
        sendLocationSettingUpdate();
        sendMicrophoneDisableSettingUpdateForCurrentUser();
    }
}
