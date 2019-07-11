/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.internal.telephony.euicc;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.Manifest;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.service.euicc.EuiccService;
import android.service.euicc.GetDefaultDownloadableSubscriptionListResult;
import android.service.euicc.GetDownloadableSubscriptionMetadataResult;
import android.service.euicc.GetEuiccProfileInfoListResult;
import android.service.euicc.IDeleteSubscriptionCallback;
import android.service.euicc.IDownloadSubscriptionCallback;
import android.service.euicc.IEraseSubscriptionsCallback;
import android.service.euicc.IEuiccService;
import android.service.euicc.IGetDefaultDownloadableSubscriptionListCallback;
import android.service.euicc.IGetDownloadableSubscriptionMetadataCallback;
import android.service.euicc.IGetEidCallback;
import android.service.euicc.IGetEuiccInfoCallback;
import android.service.euicc.IGetEuiccProfileInfoListCallback;
import android.service.euicc.IGetOtaStatusCallback;
import android.service.euicc.IOtaStatusChangedCallback;
import android.service.euicc.IRetainSubscriptionsForFactoryResetCallback;
import android.service.euicc.ISwitchToSubscriptionCallback;
import android.service.euicc.IUpdateSubscriptionNicknameCallback;
import android.telephony.SubscriptionManager;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccInfo;
import android.telephony.euicc.EuiccManager;
import android.telephony.euicc.EuiccManager.OtaStatus;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.IState;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * State machine which maintains the binding to the EuiccService implementation and issues commands.
 *
 * <p>Keeps track of the highest-priority EuiccService implementation to use. When a command comes
 * in, brings up a binding to that service, issues the command, and lingers the binding as long as
 * more commands are coming in. The binding is dropped after an idle timeout.
 */
public class EuiccConnector extends StateMachine implements ServiceConnection {
    private static final String TAG = "EuiccConnector";

    /**
     * Maximum amount of time to wait for a connection to be established after bindService returns
     * true or onServiceDisconnected is called (and no package change has occurred which should
     * force us to reestablish the binding).
     */
    private static final int BIND_TIMEOUT_MILLIS = 30000;

    /**
     * Maximum amount of idle time to hold the binding while in {@link ConnectedState}. After this,
     * the binding is dropped to free up memory as the EuiccService is not expected to be used
     * frequently as part of ongoing device operation.
     */
    @VisibleForTesting
    static final int LINGER_TIMEOUT_MILLIS = 60000;

    /**
     * Command indicating that a package change has occurred.
     *
     * <p>{@link Message#obj} is an optional package name. If set, this package has changed in a
     * way that will permanently sever any open bindings, and if we're bound to it, the binding must
     * be forcefully reestablished.
     */
    private static final int CMD_PACKAGE_CHANGE = 1;
    /** Command indicating that {@link #BIND_TIMEOUT_MILLIS} has been reached. */
    private static final int CMD_CONNECT_TIMEOUT = 2;
    /** Command indicating that {@link #LINGER_TIMEOUT_MILLIS} has been reached. */
    private static final int CMD_LINGER_TIMEOUT = 3;
    /**
     * Command indicating that the service has connected.
     *
     * <p>{@link Message#obj} is the connected {@link IEuiccService} implementation.
     */
    private static final int CMD_SERVICE_CONNECTED = 4;
    /** Command indicating that the service has disconnected. */
    private static final int CMD_SERVICE_DISCONNECTED = 5;
    /**
     * Command indicating that a command has completed and the callback should be executed.
     *
     * <p>{@link Message#obj} is a {@link Runnable} which will trigger the callback.
     */
    private static final int CMD_COMMAND_COMPLETE = 6;

    // Commands corresponding with EuiccService APIs. Keep isEuiccCommand in sync with any changes.
    private static final int CMD_GET_EID = 100;
    private static final int CMD_GET_DOWNLOADABLE_SUBSCRIPTION_METADATA = 101;
    private static final int CMD_DOWNLOAD_SUBSCRIPTION = 102;
    private static final int CMD_GET_EUICC_PROFILE_INFO_LIST = 103;
    private static final int CMD_GET_DEFAULT_DOWNLOADABLE_SUBSCRIPTION_LIST = 104;
    private static final int CMD_GET_EUICC_INFO = 105;
    private static final int CMD_DELETE_SUBSCRIPTION = 106;
    private static final int CMD_SWITCH_TO_SUBSCRIPTION = 107;
    private static final int CMD_UPDATE_SUBSCRIPTION_NICKNAME = 108;
    private static final int CMD_ERASE_SUBSCRIPTIONS = 109;
    private static final int CMD_RETAIN_SUBSCRIPTIONS = 110;
    private static final int CMD_GET_OTA_STATUS = 111;
    private static final int CMD_START_OTA_IF_NECESSARY = 112;

    private static boolean isEuiccCommand(int what) {
        return what >= CMD_GET_EID;
    }

    /** Flags to use when querying PackageManager for Euicc component implementations. */
    private static final int EUICC_QUERY_FLAGS =
            PackageManager.MATCH_SYSTEM_ONLY | PackageManager.MATCH_DEBUG_TRIAGED_MISSING
                    | PackageManager.GET_RESOLVED_FILTER;

    /**
     * Return the activity info of the activity to start for the given intent, or null if none
     * was found.
     */
    public static ActivityInfo findBestActivity(PackageManager packageManager, Intent intent) {
        List<ResolveInfo> resolveInfoList = packageManager.queryIntentActivities(intent,
                EUICC_QUERY_FLAGS);
        ActivityInfo bestComponent =
                (ActivityInfo) findBestComponent(packageManager, resolveInfoList);
        if (bestComponent == null) {
            Log.w(TAG, "No valid component found for intent: " + intent);
        }
        return bestComponent;
    }

    /**
     * Return the component info of the EuiccService to bind to, or null if none were found.
     */
    public static ComponentInfo findBestComponent(PackageManager packageManager) {
        Intent intent = new Intent(EuiccService.EUICC_SERVICE_INTERFACE);
        List<ResolveInfo> resolveInfoList =
                packageManager.queryIntentServices(intent, EUICC_QUERY_FLAGS);
        ComponentInfo bestComponent = findBestComponent(packageManager, resolveInfoList);
        if (bestComponent == null) {
            Log.w(TAG, "No valid EuiccService implementation found");
        }
        return bestComponent;
    }

    /** Base class for all command callbacks. */
    @VisibleForTesting(visibility = PACKAGE)
    public interface BaseEuiccCommandCallback {
        /** Called when a command fails because the service is or became unavailable. */
        void onEuiccServiceUnavailable();
    }

    /** Callback class for {@link #getEid}. */
    @VisibleForTesting(visibility = PACKAGE)
    public interface GetEidCommandCallback extends BaseEuiccCommandCallback {
        /** Called when the EID lookup has completed. */
        void onGetEidComplete(String eid);
    }

    /** Callback class for {@link #getOtaStatus}. */
    @VisibleForTesting(visibility = PACKAGE)
    public interface GetOtaStatusCommandCallback extends BaseEuiccCommandCallback {
        /** Called when the getting OTA status lookup has completed. */
        void onGetOtaStatusComplete(@OtaStatus int status);
    }

    /** Callback class for {@link #startOtaIfNecessary}. */
    @VisibleForTesting(visibility = PACKAGE)
    public interface OtaStatusChangedCallback extends BaseEuiccCommandCallback {
        /**
         * Called when OTA status is changed to {@link EuiccM}. */
        void onOtaStatusChanged(int status);
    }

    static class GetMetadataRequest {
        DownloadableSubscription mSubscription;
        boolean mForceDeactivateSim;
        GetMetadataCommandCallback mCallback;
    }

    /** Callback class for {@link #getDownloadableSubscriptionMetadata}. */
    @VisibleForTesting(visibility = PACKAGE)
    public interface GetMetadataCommandCallback extends BaseEuiccCommandCallback {
        /** Called when the metadata lookup has completed (though it may have failed). */
        void onGetMetadataComplete(GetDownloadableSubscriptionMetadataResult result);
    }

    static class DownloadRequest {
        DownloadableSubscription mSubscription;
        boolean mSwitchAfterDownload;
        boolean mForceDeactivateSim;
        DownloadCommandCallback mCallback;
    }

    /** Callback class for {@link #downloadSubscription}. */
    @VisibleForTesting(visibility = PACKAGE)
    public interface DownloadCommandCallback extends BaseEuiccCommandCallback {
        /** Called when the download has completed (though it may have failed). */
        void onDownloadComplete(int result);
    }

    interface GetEuiccProfileInfoListCommandCallback extends BaseEuiccCommandCallback {
        /** Called when the list has completed (though it may have failed). */
        void onListComplete(GetEuiccProfileInfoListResult result);
    }

    static class GetDefaultListRequest {
        boolean mForceDeactivateSim;
        GetDefaultListCommandCallback mCallback;
    }

    /** Callback class for {@link #getDefaultDownloadableSubscriptionList}. */
    @VisibleForTesting(visibility = PACKAGE)
    public interface GetDefaultListCommandCallback extends BaseEuiccCommandCallback {
        /** Called when the list has completed (though it may have failed). */
        void onGetDefaultListComplete(GetDefaultDownloadableSubscriptionListResult result);
    }

    /** Callback class for {@link #getEuiccInfo}. */
    @VisibleForTesting(visibility = PACKAGE)
    public interface GetEuiccInfoCommandCallback extends BaseEuiccCommandCallback {
        /** Called when the EuiccInfo lookup has completed. */
        void onGetEuiccInfoComplete(EuiccInfo euiccInfo);
    }

    static class DeleteRequest {
        String mIccid;
        DeleteCommandCallback mCallback;
    }

    /** Callback class for {@link #deleteSubscription}. */
    @VisibleForTesting(visibility = PACKAGE)
    public interface DeleteCommandCallback extends BaseEuiccCommandCallback {
        /** Called when the delete has completed (though it may have failed). */
        void onDeleteComplete(int result);
    }

    static class SwitchRequest {
        @Nullable String mIccid;
        boolean mForceDeactivateSim;
        SwitchCommandCallback mCallback;
    }

    /** Callback class for {@link #switchToSubscription}. */
    @VisibleForTesting(visibility = PACKAGE)
    public interface SwitchCommandCallback extends BaseEuiccCommandCallback {
        /** Called when the switch has completed (though it may have failed). */
        void onSwitchComplete(int result);
    }

    static class UpdateNicknameRequest {
        String mIccid;
        String mNickname;
        UpdateNicknameCommandCallback mCallback;
    }

    /** Callback class for {@link #updateSubscriptionNickname}. */
    @VisibleForTesting(visibility = PACKAGE)
    public interface UpdateNicknameCommandCallback extends BaseEuiccCommandCallback {
        /** Called when the update has completed (though it may have failed). */
        void onUpdateNicknameComplete(int result);
    }

    /** Callback class for {@link #eraseSubscriptions}. */
    @VisibleForTesting(visibility = PACKAGE)
    public interface EraseCommandCallback extends BaseEuiccCommandCallback {
        /** Called when the erase has completed (though it may have failed). */
        void onEraseComplete(int result);
    }

    /** Callback class for {@link #retainSubscriptions}. */
    @VisibleForTesting(visibility = PACKAGE)
    public interface RetainSubscriptionsCommandCallback extends BaseEuiccCommandCallback {
        /** Called when the retain command has completed (though it may have failed). */
        void onRetainSubscriptionsComplete(int result);
    }

    private Context mContext;
    private PackageManager mPm;

    private final PackageMonitor mPackageMonitor = new EuiccPackageMonitor();
    private final BroadcastReceiver mUserUnlockedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                // On user unlock, new components might become available, so rebind if needed. This
                // can never make a component unavailable so there's never a need to force a
                // rebind.
                sendMessage(CMD_PACKAGE_CHANGE);
            }
        }
    };

    /** Set to the current component we should bind to except in {@link UnavailableState}. */
    private @Nullable ServiceInfo mSelectedComponent;

    /** Set to the currently connected EuiccService implementation in {@link ConnectedState}. */
    private @Nullable IEuiccService mEuiccService;

    /** The callbacks for all (asynchronous) commands which are currently in flight. */
    private Set<BaseEuiccCommandCallback> mActiveCommandCallbacks = new ArraySet<>();

    @VisibleForTesting(visibility = PACKAGE) public UnavailableState mUnavailableState;
    @VisibleForTesting(visibility = PACKAGE) public AvailableState mAvailableState;
    @VisibleForTesting(visibility = PACKAGE) public BindingState mBindingState;
    @VisibleForTesting(visibility = PACKAGE) public DisconnectedState mDisconnectedState;
    @VisibleForTesting(visibility = PACKAGE) public ConnectedState mConnectedState;

    EuiccConnector(Context context) {
        super(TAG);
        init(context);
    }

    @VisibleForTesting(visibility = PACKAGE)
    public EuiccConnector(Context context, Looper looper) {
        super(TAG, looper);
        init(context);
    }

    private void init(Context context) {
        mContext = context;
        mPm = context.getPackageManager();

        // Unavailable/Available both monitor for package changes and update mSelectedComponent but
        // do not need to adjust the binding.
        mUnavailableState = new UnavailableState();
        addState(mUnavailableState);
        mAvailableState = new AvailableState();
        addState(mAvailableState, mUnavailableState);

        mBindingState = new BindingState();
        addState(mBindingState);

        // Disconnected/Connected both monitor for package changes and reestablish the active
        // binding if necessary.
        mDisconnectedState = new DisconnectedState();
        addState(mDisconnectedState);
        mConnectedState = new ConnectedState();
        addState(mConnectedState, mDisconnectedState);

        mSelectedComponent = findBestComponent();
        setInitialState(mSelectedComponent != null ? mAvailableState : mUnavailableState);

        mPackageMonitor.register(mContext, null /* thread */, false /* externalStorage */);
        mContext.registerReceiver(
                mUserUnlockedReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));

        start();
    }

    @Override
    public void onHalting() {
        mPackageMonitor.unregister();
        mContext.unregisterReceiver(mUserUnlockedReceiver);
    }

    /** Asynchronously fetch the EID. */
    @VisibleForTesting(visibility = PACKAGE)
    public void getEid(GetEidCommandCallback callback) {
        sendMessage(CMD_GET_EID, callback);
    }

    /** Asynchronously get OTA status. */
    @VisibleForTesting(visibility = PACKAGE)
    public void getOtaStatus(GetOtaStatusCommandCallback callback) {
        sendMessage(CMD_GET_OTA_STATUS, callback);
    }

    /** Asynchronously perform OTA update. */
    @VisibleForTesting(visibility = PACKAGE)
    public void startOtaIfNecessary(OtaStatusChangedCallback callback) {
        sendMessage(CMD_START_OTA_IF_NECESSARY, callback);
    }

    /** Asynchronously fetch metadata for the given downloadable subscription. */
    @VisibleForTesting(visibility = PACKAGE)
    public void getDownloadableSubscriptionMetadata(DownloadableSubscription subscription,
            boolean forceDeactivateSim, GetMetadataCommandCallback callback) {
        GetMetadataRequest request =
                new GetMetadataRequest();
        request.mSubscription = subscription;
        request.mForceDeactivateSim = forceDeactivateSim;
        request.mCallback = callback;
        sendMessage(CMD_GET_DOWNLOADABLE_SUBSCRIPTION_METADATA, request);
    }

    /** Asynchronously download the given subscription. */
    @VisibleForTesting(visibility = PACKAGE)
    public void downloadSubscription(DownloadableSubscription subscription,
            boolean switchAfterDownload, boolean forceDeactivateSim,
            DownloadCommandCallback callback) {
        DownloadRequest request = new DownloadRequest();
        request.mSubscription = subscription;
        request.mSwitchAfterDownload = switchAfterDownload;
        request.mForceDeactivateSim = forceDeactivateSim;
        request.mCallback = callback;
        sendMessage(CMD_DOWNLOAD_SUBSCRIPTION, request);
    }

    void getEuiccProfileInfoList(GetEuiccProfileInfoListCommandCallback callback) {
        sendMessage(CMD_GET_EUICC_PROFILE_INFO_LIST, callback);
    }

    /** Asynchronously fetch the default downloadable subscription list. */
    @VisibleForTesting(visibility = PACKAGE)
    public void getDefaultDownloadableSubscriptionList(
            boolean forceDeactivateSim, GetDefaultListCommandCallback callback) {
        GetDefaultListRequest request = new GetDefaultListRequest();
        request.mForceDeactivateSim = forceDeactivateSim;
        request.mCallback = callback;
        sendMessage(CMD_GET_DEFAULT_DOWNLOADABLE_SUBSCRIPTION_LIST, request);
    }

    /** Asynchronously fetch the {@link EuiccInfo}. */
    @VisibleForTesting(visibility = PACKAGE)
    public void getEuiccInfo(GetEuiccInfoCommandCallback callback) {
        sendMessage(CMD_GET_EUICC_INFO, callback);
    }

    /** Asynchronously delete the given subscription. */
    @VisibleForTesting(visibility = PACKAGE)
    public void deleteSubscription(String iccid, DeleteCommandCallback callback) {
        DeleteRequest request = new DeleteRequest();
        request.mIccid = iccid;
        request.mCallback = callback;
        sendMessage(CMD_DELETE_SUBSCRIPTION, request);
    }

    /** Asynchronously switch to the given subscription. */
    @VisibleForTesting(visibility = PACKAGE)
    public void switchToSubscription(@Nullable String iccid, boolean forceDeactivateSim,
            SwitchCommandCallback callback) {
        SwitchRequest request = new SwitchRequest();
        request.mIccid = iccid;
        request.mForceDeactivateSim = forceDeactivateSim;
        request.mCallback = callback;
        sendMessage(CMD_SWITCH_TO_SUBSCRIPTION, request);
    }

    /** Asynchronously update the nickname of the given subscription. */
    @VisibleForTesting(visibility = PACKAGE)
    public void updateSubscriptionNickname(
            String iccid, String nickname, UpdateNicknameCommandCallback callback) {
        UpdateNicknameRequest request = new UpdateNicknameRequest();
        request.mIccid = iccid;
        request.mNickname = nickname;
        request.mCallback = callback;
        sendMessage(CMD_UPDATE_SUBSCRIPTION_NICKNAME, request);
    }

    /** Asynchronously erase all profiles on the eUICC. */
    @VisibleForTesting(visibility = PACKAGE)
    public void eraseSubscriptions(EraseCommandCallback callback) {
        sendMessage(CMD_ERASE_SUBSCRIPTIONS, callback);
    }

    /** Asynchronously ensure that all profiles will be retained on the next factory reset. */
    @VisibleForTesting(visibility = PACKAGE)
    public void retainSubscriptions(RetainSubscriptionsCommandCallback callback) {
        sendMessage(CMD_RETAIN_SUBSCRIPTIONS, callback);
    }

    /**
     * State in which no EuiccService is available.
     *
     * <p>All incoming commands will be rejected through
     * {@link BaseEuiccCommandCallback#onEuiccServiceUnavailable()}.
     *
     * <p>Package state changes will lead to transitions between {@link UnavailableState} and
     * {@link AvailableState} depending on whether an EuiccService becomes unavailable or
     * available.
     */
    private class UnavailableState extends State {
        @Override
        public boolean processMessage(Message message) {
            if (message.what == CMD_PACKAGE_CHANGE) {
                mSelectedComponent = findBestComponent();
                if (mSelectedComponent != null) {
                    transitionTo(mAvailableState);
                } else if (getCurrentState() != mUnavailableState) {
                    transitionTo(mUnavailableState);
                }
                return HANDLED;
            } else if (isEuiccCommand(message.what)) {
                BaseEuiccCommandCallback callback = getCallback(message);
                callback.onEuiccServiceUnavailable();
                return HANDLED;
            }

            return NOT_HANDLED;
        }
    }

    /**
     * State in which a EuiccService is available, but no binding is established or in the process
     * of being established.
     *
     * <p>If a command is received, this state will defer the message and enter {@link BindingState}
     * to bring up the binding.
     */
    private class AvailableState extends State {
        @Override
        public boolean processMessage(Message message) {
            if (isEuiccCommand(message.what)) {
                deferMessage(message);
                transitionTo(mBindingState);
                return HANDLED;
            }

            return NOT_HANDLED;
        }
    }

    /**
     * State in which we are binding to the current EuiccService.
     *
     * <p>This is a transient state. If bindService returns true, we enter {@link DisconnectedState}
     * while waiting for the binding to be established. If it returns false, we move back to
     * {@link AvailableState}.
     *
     * <p>Any received messages will be deferred.
     */
    private class BindingState extends State {
        @Override
        public void enter() {
            if (createBinding()) {
                transitionTo(mDisconnectedState);
            } else {
                // createBinding() should generally not return false since we've already performed
                // Intent resolution, but it's always possible that the package state changes
                // asynchronously. Transition to available for now, and if the package state has
                // changed, we'll process that event and move to mUnavailableState as needed.
                transitionTo(mAvailableState);
            }
        }

        @Override
        public boolean processMessage(Message message) {
            deferMessage(message);
            return HANDLED;
        }
    }

    /**
     * State in which a binding is established, but not currently connected.
     *
     * <p>We wait up to {@link #BIND_TIMEOUT_MILLIS} for the binding to establish. If it doesn't,
     * we go back to {@link AvailableState} to try again.
     *
     * <p>Package state changes will cause us to unbind and move to {@link BindingState} to
     * reestablish the binding if the selected component has changed or if a forced rebind is
     * necessary.
     *
     * <p>Any received commands will be deferred.
     */
    private class DisconnectedState extends State {
        @Override
        public void enter() {
            sendMessageDelayed(CMD_CONNECT_TIMEOUT, BIND_TIMEOUT_MILLIS);
        }

        @Override
        public boolean processMessage(Message message) {
            if (message.what == CMD_SERVICE_CONNECTED) {
                mEuiccService = (IEuiccService) message.obj;
                transitionTo(mConnectedState);
                return HANDLED;
            } else if (message.what == CMD_PACKAGE_CHANGE) {
                ServiceInfo bestComponent = findBestComponent();
                String affectedPackage = (String) message.obj;
                boolean isSameComponent;
                if (bestComponent == null) {
                    isSameComponent = mSelectedComponent != null;
                } else {
                    isSameComponent = mSelectedComponent == null
                            || Objects.equals(
                                    bestComponent.getComponentName(),
                                    mSelectedComponent.getComponentName());
                }
                boolean forceRebind = bestComponent != null
                        && Objects.equals(bestComponent.packageName, affectedPackage);
                if (!isSameComponent || forceRebind) {
                    unbind();
                    mSelectedComponent = bestComponent;
                    if (mSelectedComponent == null) {
                        transitionTo(mUnavailableState);
                    } else {
                        transitionTo(mBindingState);
                    }
                }
                return HANDLED;
            } else if (message.what == CMD_CONNECT_TIMEOUT) {
                transitionTo(mAvailableState);
                return HANDLED;
            } else if (isEuiccCommand(message.what)) {
                deferMessage(message);
                return HANDLED;
            }

            return NOT_HANDLED;
        }
    }

    /**
     * State in which the binding is connected.
     *
     * <p>Commands will be processed as long as we're in this state. We wait up to
     * {@link #LINGER_TIMEOUT_MILLIS} between commands; if this timeout is reached, we will drop the
     * binding until the next command is received.
     */
    private class ConnectedState extends State {
        @Override
        public void enter() {
            removeMessages(CMD_CONNECT_TIMEOUT);
            sendMessageDelayed(CMD_LINGER_TIMEOUT, LINGER_TIMEOUT_MILLIS);
        }

        @Override
        public boolean processMessage(Message message) {
            if (message.what == CMD_SERVICE_DISCONNECTED) {
                mEuiccService = null;
                transitionTo(mDisconnectedState);
                return HANDLED;
            } else if (message.what == CMD_LINGER_TIMEOUT) {
                unbind();
                transitionTo(mAvailableState);
                return HANDLED;
            } else if (message.what == CMD_COMMAND_COMPLETE) {
                Runnable runnable = (Runnable) message.obj;
                runnable.run();
                return HANDLED;
            } else if (isEuiccCommand(message.what)) {
                final BaseEuiccCommandCallback callback = getCallback(message);
                onCommandStart(callback);
                // TODO(b/36260308): Plumb through an actual SIM slot ID.
                int slotId = SubscriptionManager.INVALID_SIM_SLOT_INDEX;
                try {
                    switch (message.what) {
                        case CMD_GET_EID: {
                            mEuiccService.getEid(slotId,
                                    new IGetEidCallback.Stub() {
                                        @Override
                                        public void onSuccess(String eid) {
                                            sendMessage(CMD_COMMAND_COMPLETE, (Runnable) () -> {
                                                ((GetEidCommandCallback) callback)
                                                        .onGetEidComplete(eid);
                                                onCommandEnd(callback);
                                            });
                                        }
                                    });
                            break;
                        }
                        case CMD_GET_DOWNLOADABLE_SUBSCRIPTION_METADATA: {
                            GetMetadataRequest request = (GetMetadataRequest) message.obj;
                            mEuiccService.getDownloadableSubscriptionMetadata(slotId,
                                    request.mSubscription,
                                    request.mForceDeactivateSim,
                                    new IGetDownloadableSubscriptionMetadataCallback.Stub() {
                                        @Override
                                        public void onComplete(
                                                GetDownloadableSubscriptionMetadataResult result) {
                                            sendMessage(CMD_COMMAND_COMPLETE, (Runnable) () -> {
                                                ((GetMetadataCommandCallback) callback)
                                                        .onGetMetadataComplete(result);
                                                onCommandEnd(callback);
                                            });
                                        }
                                    });
                            break;
                        }
                        case CMD_DOWNLOAD_SUBSCRIPTION: {
                            DownloadRequest request = (DownloadRequest) message.obj;
                            mEuiccService.downloadSubscription(slotId,
                                    request.mSubscription,
                                    request.mSwitchAfterDownload,
                                    request.mForceDeactivateSim,
                                    new IDownloadSubscriptionCallback.Stub() {
                                        @Override
                                        public void onComplete(int result) {
                                            sendMessage(CMD_COMMAND_COMPLETE, (Runnable) () -> {
                                                ((DownloadCommandCallback) callback)
                                                        .onDownloadComplete(result);
                                                onCommandEnd(callback);
                                            });
                                        }
                                    });
                            break;
                        }
                        case CMD_GET_EUICC_PROFILE_INFO_LIST: {
                            mEuiccService.getEuiccProfileInfoList(slotId,
                                    new IGetEuiccProfileInfoListCallback.Stub() {
                                        @Override
                                        public void onComplete(
                                                GetEuiccProfileInfoListResult result) {
                                            sendMessage(CMD_COMMAND_COMPLETE, (Runnable) () -> {
                                                ((GetEuiccProfileInfoListCommandCallback) callback)
                                                        .onListComplete(result);
                                                onCommandEnd(callback);
                                            });
                                        }
                                    });
                            break;
                        }
                        case CMD_GET_DEFAULT_DOWNLOADABLE_SUBSCRIPTION_LIST: {
                            GetDefaultListRequest request = (GetDefaultListRequest) message.obj;
                            mEuiccService.getDefaultDownloadableSubscriptionList(slotId,
                                    request.mForceDeactivateSim,
                                    new IGetDefaultDownloadableSubscriptionListCallback.Stub() {
                                        @Override
                                        public void onComplete(
                                                GetDefaultDownloadableSubscriptionListResult result
                                        ) {
                                            sendMessage(CMD_COMMAND_COMPLETE, (Runnable) () -> {
                                                ((GetDefaultListCommandCallback) callback)
                                                        .onGetDefaultListComplete(result);
                                                onCommandEnd(callback);
                                            });
                                        }
                                    });
                            break;
                        }
                        case CMD_GET_EUICC_INFO: {
                            mEuiccService.getEuiccInfo(slotId,
                                    new IGetEuiccInfoCallback.Stub() {
                                        @Override
                                        public void onSuccess(EuiccInfo euiccInfo) {
                                            sendMessage(CMD_COMMAND_COMPLETE, (Runnable) () -> {
                                                ((GetEuiccInfoCommandCallback) callback)
                                                        .onGetEuiccInfoComplete(euiccInfo);
                                                onCommandEnd(callback);
                                            });
                                        }
                                    });
                            break;
                        }
                        case CMD_DELETE_SUBSCRIPTION: {
                            DeleteRequest request = (DeleteRequest) message.obj;
                            mEuiccService.deleteSubscription(slotId, request.mIccid,
                                    new IDeleteSubscriptionCallback.Stub() {
                                        @Override
                                        public void onComplete(int result) {
                                            sendMessage(CMD_COMMAND_COMPLETE, (Runnable) () -> {
                                                ((DeleteCommandCallback) callback)
                                                        .onDeleteComplete(result);
                                                onCommandEnd(callback);
                                            });
                                        }
                                    });
                            break;
                        }
                        case CMD_SWITCH_TO_SUBSCRIPTION: {
                            SwitchRequest request = (SwitchRequest) message.obj;
                            mEuiccService.switchToSubscription(slotId, request.mIccid,
                                    request.mForceDeactivateSim,
                                    new ISwitchToSubscriptionCallback.Stub() {
                                        @Override
                                        public void onComplete(int result) {
                                            sendMessage(CMD_COMMAND_COMPLETE, (Runnable) () -> {
                                                ((SwitchCommandCallback) callback)
                                                        .onSwitchComplete(result);
                                                onCommandEnd(callback);
                                            });
                                        }
                                    });
                            break;
                        }
                        case CMD_UPDATE_SUBSCRIPTION_NICKNAME: {
                            UpdateNicknameRequest request = (UpdateNicknameRequest) message.obj;
                            mEuiccService.updateSubscriptionNickname(slotId, request.mIccid,
                                    request.mNickname,
                                    new IUpdateSubscriptionNicknameCallback.Stub() {
                                        @Override
                                        public void onComplete(int result) {
                                            sendMessage(CMD_COMMAND_COMPLETE, (Runnable) () -> {
                                                ((UpdateNicknameCommandCallback) callback)
                                                        .onUpdateNicknameComplete(result);
                                                onCommandEnd(callback);
                                            });
                                        }
                                    });
                            break;
                        }
                        case CMD_ERASE_SUBSCRIPTIONS: {
                            mEuiccService.eraseSubscriptions(slotId,
                                    new IEraseSubscriptionsCallback.Stub() {
                                        @Override
                                        public void onComplete(int result) {
                                            sendMessage(CMD_COMMAND_COMPLETE, (Runnable) () -> {
                                                ((EraseCommandCallback) callback)
                                                        .onEraseComplete(result);
                                                onCommandEnd(callback);
                                            });
                                        }
                                    });
                            break;
                        }
                        case CMD_RETAIN_SUBSCRIPTIONS: {
                            mEuiccService.retainSubscriptionsForFactoryReset(slotId,
                                    new IRetainSubscriptionsForFactoryResetCallback.Stub() {
                                        @Override
                                        public void onComplete(int result) {
                                            sendMessage(CMD_COMMAND_COMPLETE, (Runnable) () -> {
                                                ((RetainSubscriptionsCommandCallback) callback)
                                                        .onRetainSubscriptionsComplete(result);
                                                onCommandEnd(callback);
                                            });
                                        }
                                    });
                            break;
                        }
                        case CMD_GET_OTA_STATUS: {
                            mEuiccService.getOtaStatus(slotId,
                                    new IGetOtaStatusCallback.Stub() {
                                        @Override
                                        public void onSuccess(@OtaStatus int status) {
                                            sendMessage(CMD_COMMAND_COMPLETE, (Runnable) () -> {
                                                ((GetOtaStatusCommandCallback) callback)
                                                        .onGetOtaStatusComplete(status);
                                                onCommandEnd(callback);
                                            });
                                        }
                                    });
                            break;
                        }
                        case CMD_START_OTA_IF_NECESSARY: {
                            mEuiccService.startOtaIfNecessary(slotId,
                                    new IOtaStatusChangedCallback.Stub() {
                                        @Override
                                        public void onOtaStatusChanged(int status)
                                                throws RemoteException {
                                            if (status == EuiccManager.EUICC_OTA_IN_PROGRESS) {
                                                sendMessage(CMD_COMMAND_COMPLETE, (Runnable) () -> {
                                                    ((OtaStatusChangedCallback) callback)
                                                            .onOtaStatusChanged(status);
                                                });
                                            } else {
                                                sendMessage(CMD_COMMAND_COMPLETE, (Runnable) () -> {
                                                    ((OtaStatusChangedCallback) callback)
                                                            .onOtaStatusChanged(status);
                                                    onCommandEnd(callback);
                                                });
                                            }
                                        }
                                    });
                            break;
                        }
                        default: {
                            Log.wtf(TAG, "Unimplemented eUICC command: " + message.what);
                            callback.onEuiccServiceUnavailable();
                            onCommandEnd(callback);
                            return HANDLED;
                        }
                    }
                } catch (Exception e) {
                    // If this is a RemoteException, we expect to be disconnected soon. For other
                    // exceptions, this is a bug in the EuiccService implementation, but we must
                    // not let it crash the phone process.
                    Log.w(TAG, "Exception making binder call to EuiccService", e);
                    callback.onEuiccServiceUnavailable();
                    onCommandEnd(callback);
                }

                return HANDLED;
            }

            return NOT_HANDLED;
        }

        @Override
        public void exit() {
            removeMessages(CMD_LINGER_TIMEOUT);
            // Dispatch callbacks for all in-flight commands; they will no longer succeed. (The
            // remote process cannot possibly trigger a callback at this stage because the
            // connection has dropped).
            for (BaseEuiccCommandCallback callback : mActiveCommandCallbacks) {
                callback.onEuiccServiceUnavailable();
            }
            mActiveCommandCallbacks.clear();
        }
    }

    private static BaseEuiccCommandCallback getCallback(Message message) {
        switch (message.what) {
            case CMD_GET_EID:
            case CMD_GET_EUICC_PROFILE_INFO_LIST:
            case CMD_GET_EUICC_INFO:
            case CMD_ERASE_SUBSCRIPTIONS:
            case CMD_RETAIN_SUBSCRIPTIONS:
            case CMD_GET_OTA_STATUS:
            case CMD_START_OTA_IF_NECESSARY:
                return (BaseEuiccCommandCallback) message.obj;
            case CMD_GET_DOWNLOADABLE_SUBSCRIPTION_METADATA:
                return ((GetMetadataRequest) message.obj).mCallback;
            case CMD_DOWNLOAD_SUBSCRIPTION:
                return ((DownloadRequest) message.obj).mCallback;
            case CMD_GET_DEFAULT_DOWNLOADABLE_SUBSCRIPTION_LIST:
                return ((GetDefaultListRequest) message.obj).mCallback;
            case CMD_DELETE_SUBSCRIPTION:
                return ((DeleteRequest) message.obj).mCallback;
            case CMD_SWITCH_TO_SUBSCRIPTION:
                return ((SwitchRequest) message.obj).mCallback;
            case CMD_UPDATE_SUBSCRIPTION_NICKNAME:
                return ((UpdateNicknameRequest) message.obj).mCallback;
            default:
                throw new IllegalArgumentException("Unsupported message: " + message.what);
        }
    }

    /** Call this at the beginning of the execution of any command. */
    private void onCommandStart(BaseEuiccCommandCallback callback) {
        mActiveCommandCallbacks.add(callback);
        removeMessages(CMD_LINGER_TIMEOUT);
    }

    /** Call this at the end of execution of any command (whether or not it succeeded). */
    private void onCommandEnd(BaseEuiccCommandCallback callback) {
        if (!mActiveCommandCallbacks.remove(callback)) {
            Log.wtf(TAG, "Callback already removed from mActiveCommandCallbacks");
        }
        if (mActiveCommandCallbacks.isEmpty()) {
            sendMessageDelayed(CMD_LINGER_TIMEOUT, LINGER_TIMEOUT_MILLIS);
        }
    }

    /** Return the service info of the EuiccService to bind to, or null if none were found. */
    @Nullable
    private ServiceInfo findBestComponent() {
        return (ServiceInfo) findBestComponent(mPm);
    }

    /**
     * Bring up a binding to the currently-selected component.
     *
     * <p>Returns true if we've successfully bound to the service.
     */
    private boolean createBinding() {
        if (mSelectedComponent == null) {
            Log.wtf(TAG, "Attempting to create binding but no component is selected");
            return false;
        }
        Intent intent = new Intent(EuiccService.EUICC_SERVICE_INTERFACE);
        intent.setComponent(mSelectedComponent.getComponentName());
        // We bind this as a foreground service because it is operating directly on the SIM, and we
        // do not want it subjected to power-savings restrictions while doing so.
        return mContext.bindService(intent, this,
                Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE);
    }

    private void unbind() {
        mEuiccService = null;
        mContext.unbindService(this);
    }

    private static ComponentInfo findBestComponent(
            PackageManager packageManager, List<ResolveInfo> resolveInfoList) {
        int bestPriority = Integer.MIN_VALUE;
        ComponentInfo bestComponent = null;
        if (resolveInfoList != null) {
            for (ResolveInfo resolveInfo : resolveInfoList) {
                if (!isValidEuiccComponent(packageManager, resolveInfo)) {
                    continue;
                }

                if (resolveInfo.filter.getPriority() > bestPriority) {
                    bestPriority = resolveInfo.filter.getPriority();
                    bestComponent = resolveInfo.getComponentInfo();
                }
            }
        }

        return bestComponent;
    }

    private static boolean isValidEuiccComponent(
            PackageManager packageManager, ResolveInfo resolveInfo) {
        ComponentInfo componentInfo = resolveInfo.getComponentInfo();
        String packageName = componentInfo.getComponentName().getPackageName();

        // Verify that the app is privileged (via granting of a privileged permission).
        if (packageManager.checkPermission(
                Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS, packageName)
                        != PackageManager.PERMISSION_GRANTED) {
            Log.wtf(TAG, "Package " + packageName
                    + " does not declare WRITE_EMBEDDED_SUBSCRIPTIONS");
            return false;
        }

        // Verify that only the system can access the component.
        final String permission;
        if (componentInfo instanceof ServiceInfo) {
            permission = ((ServiceInfo) componentInfo).permission;
        } else if (componentInfo instanceof ActivityInfo) {
            permission = ((ActivityInfo) componentInfo).permission;
        } else {
            throw new IllegalArgumentException("Can only verify services/activities");
        }
        if (!TextUtils.equals(permission, Manifest.permission.BIND_EUICC_SERVICE)) {
            Log.wtf(TAG, "Package " + packageName
                    + " does not require the BIND_EUICC_SERVICE permission");
            return false;
        }

        // Verify that the component declares a priority.
        if (resolveInfo.filter == null || resolveInfo.filter.getPriority() == 0) {
            Log.wtf(TAG, "Package " + packageName + " does not specify a priority");
            return false;
        }
        return true;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        IEuiccService euiccService = IEuiccService.Stub.asInterface(service);
        sendMessage(CMD_SERVICE_CONNECTED, euiccService);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        sendMessage(CMD_SERVICE_DISCONNECTED);
    }

    private class EuiccPackageMonitor extends PackageMonitor {
        @Override
        public void onPackageAdded(String packageName, int reason) {
            sendPackageChange(packageName, true /* forceUnbindForThisPackage */);
        }

        @Override
        public void onPackageRemoved(String packageName, int reason) {
            sendPackageChange(packageName, true /* forceUnbindForThisPackage */);
        }

        @Override
        public void onPackageUpdateFinished(String packageName, int uid) {
            sendPackageChange(packageName, true /* forceUnbindForThisPackage */);
        }

        @Override
        public void onPackageModified(String packageName) {
            sendPackageChange(packageName, false /* forceUnbindForThisPackage */);
        }

        @Override
        public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
            if (doit) {
                for (String packageName : packages) {
                    sendPackageChange(packageName, true /* forceUnbindForThisPackage */);
                }
            }
            return super.onHandleForceStop(intent, packages, uid, doit);
        }

        private void sendPackageChange(String packageName, boolean forceUnbindForThisPackage) {
            sendMessage(CMD_PACKAGE_CHANGE, forceUnbindForThisPackage ? packageName : null);
        }
    }

    @Override
    protected void unhandledMessage(Message msg) {
        IState state = getCurrentState();
        Log.wtf(TAG, "Unhandled message " + msg.what + " in state "
                + (state == null ? "null" : state.getName()));
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);
        pw.println("mSelectedComponent=" + mSelectedComponent);
        pw.println("mEuiccService=" + mEuiccService);
        pw.println("mActiveCommandCount=" + mActiveCommandCallbacks.size());
    }
}
