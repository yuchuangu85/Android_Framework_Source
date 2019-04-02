/*
 * Copyright (C) 2013 The Android Open Source Project
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

import static com.android.server.wifi.ConnectToNetworkNotificationBuilder.ACTION_CONNECT_TO_NETWORK;
import static com.android.server.wifi.ConnectToNetworkNotificationBuilder.ACTION_PICK_WIFI_NETWORK;
import static com.android.server.wifi.ConnectToNetworkNotificationBuilder.ACTION_PICK_WIFI_NETWORK_AFTER_CONNECT_FAILURE;
import static com.android.server.wifi.ConnectToNetworkNotificationBuilder.ACTION_USER_DISMISSED_NOTIFICATION;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.server.wifi.nano.WifiMetricsProto.ConnectToNetworkNotificationAndActionCount;
import com.android.server.wifi.util.ScanResultUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;

/**
 * Takes care of handling the "open wi-fi network available" notification
 *
 * NOTE: These API's are not thread safe and should only be used from WifiStateMachine thread.
 * @hide
 */
public class OpenNetworkNotifier {

    private static final String TAG = "OpenNetworkNotifier";

    /** Time in milliseconds to display the Connecting notification. */
    private static final int TIME_TO_SHOW_CONNECTING_MILLIS = 10000;

    /** Time in milliseconds to display the Connected notification. */
    private static final int TIME_TO_SHOW_CONNECTED_MILLIS = 5000;

    /** Time in milliseconds to display the Failed To Connect notification. */
    private static final int TIME_TO_SHOW_FAILED_MILLIS = 5000;

    /** The state of the notification */
    @IntDef({
            STATE_NO_NOTIFICATION,
            STATE_SHOWING_RECOMMENDATION_NOTIFICATION,
            STATE_CONNECTING_IN_NOTIFICATION,
            STATE_CONNECTED_NOTIFICATION,
            STATE_CONNECT_FAILED_NOTIFICATION
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface State {}

    /** No recommendation is made and no notifications are shown. */
    private static final int STATE_NO_NOTIFICATION = 0;
    /** The initial notification recommending an open network to connect to is shown. */
    private static final int STATE_SHOWING_RECOMMENDATION_NOTIFICATION = 1;
    /** The notification of status of connecting to the recommended network is shown. */
    private static final int STATE_CONNECTING_IN_NOTIFICATION = 2;
    /** The notification that the connection to the recommended network was successful is shown. */
    private static final int STATE_CONNECTED_NOTIFICATION = 3;
    /** The notification to show that connection to the recommended network failed is shown. */
    private static final int STATE_CONNECT_FAILED_NOTIFICATION = 4;

    /** Current state of the notification. */
    @State private int mState = STATE_NO_NOTIFICATION;

    /** Identifier of the {@link SsidSetStoreData}. */
    private static final String STORE_DATA_IDENTIFIER = "OpenNetworkNotifierBlacklist";
    /**
     * The {@link Clock#getWallClockMillis()} must be at least this value for us
     * to show the notification again.
     */
    private long mNotificationRepeatTime;
    /**
     * When a notification is shown, we wait this amount before possibly showing it again.
     */
    private final long mNotificationRepeatDelay;
    /** Default repeat delay in seconds. */
    @VisibleForTesting
    static final int DEFAULT_REPEAT_DELAY_SEC = 900;

    /** Whether the user has set the setting to show the 'available networks' notification. */
    private boolean mSettingEnabled;
    /** Whether the screen is on or not. */
    private boolean mScreenOn;

    /** List of SSIDs blacklisted from recommendation. */
    private final Set<String> mBlacklistedSsids;

    private final Context mContext;
    private final Handler mHandler;
    private final FrameworkFacade mFrameworkFacade;
    private final WifiMetrics mWifiMetrics;
    private final Clock mClock;
    private final WifiConfigManager mConfigManager;
    private final WifiStateMachine mWifiStateMachine;
    private final Messenger mSrcMessenger;
    private final OpenNetworkRecommender mOpenNetworkRecommender;
    private final ConnectToNetworkNotificationBuilder mNotificationBuilder;

    private ScanResult mRecommendedNetwork;

    OpenNetworkNotifier(
            Context context,
            Looper looper,
            FrameworkFacade framework,
            Clock clock,
            WifiMetrics wifiMetrics,
            WifiConfigManager wifiConfigManager,
            WifiConfigStore wifiConfigStore,
            WifiStateMachine wifiStateMachine,
            OpenNetworkRecommender openNetworkRecommender,
            ConnectToNetworkNotificationBuilder connectToNetworkNotificationBuilder) {
        mContext = context;
        mHandler = new Handler(looper);
        mFrameworkFacade = framework;
        mWifiMetrics = wifiMetrics;
        mClock = clock;
        mConfigManager = wifiConfigManager;
        mWifiStateMachine = wifiStateMachine;
        mOpenNetworkRecommender = openNetworkRecommender;
        mNotificationBuilder = connectToNetworkNotificationBuilder;
        mScreenOn = false;
        mSrcMessenger = new Messenger(new Handler(looper, mConnectionStateCallback));

        mBlacklistedSsids = new ArraySet<>();
        wifiConfigStore.registerStoreData(new SsidSetStoreData(
                STORE_DATA_IDENTIFIER, new OpenNetworkNotifierStoreData()));

        // Setting is in seconds
        mNotificationRepeatDelay = mFrameworkFacade.getIntegerSetting(context,
                Settings.Global.WIFI_NETWORKS_AVAILABLE_REPEAT_DELAY,
                DEFAULT_REPEAT_DELAY_SEC) * 1000L;
        NotificationEnabledSettingObserver settingObserver = new NotificationEnabledSettingObserver(
                mHandler);
        settingObserver.register();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USER_DISMISSED_NOTIFICATION);
        filter.addAction(ACTION_CONNECT_TO_NETWORK);
        filter.addAction(ACTION_PICK_WIFI_NETWORK);
        filter.addAction(ACTION_PICK_WIFI_NETWORK_AFTER_CONNECT_FAILURE);
        mContext.registerReceiver(
                mBroadcastReceiver, filter, null /* broadcastPermission */, mHandler);
    }

    private final BroadcastReceiver mBroadcastReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    switch (intent.getAction()) {
                        case ACTION_USER_DISMISSED_NOTIFICATION:
                            handleUserDismissedAction();
                            break;
                        case ACTION_CONNECT_TO_NETWORK:
                            handleConnectToNetworkAction();
                            break;
                        case ACTION_PICK_WIFI_NETWORK:
                            handleSeeAllNetworksAction();
                            break;
                        case ACTION_PICK_WIFI_NETWORK_AFTER_CONNECT_FAILURE:
                            handlePickWifiNetworkAfterConnectFailure();
                            break;
                        default:
                            Log.e(TAG, "Unknown action " + intent.getAction());
                    }
                }
            };

    private final Handler.Callback mConnectionStateCallback = (Message msg) -> {
        switch (msg.what) {
            // Success here means that an attempt to connect to the network has been initiated.
            // Successful connection updates are received via the
            // WifiConnectivityManager#handleConnectionStateChanged() callback.
            case WifiManager.CONNECT_NETWORK_SUCCEEDED:
                break;
            case WifiManager.CONNECT_NETWORK_FAILED:
                handleConnectionAttemptFailedToSend();
                break;
            default:
                Log.e(TAG, "Unknown message " + msg.what);
        }
        return true;
    };

    /**
     * Clears the pending notification. This is called by {@link WifiConnectivityManager} on stop.
     *
     * @param resetRepeatTime resets the time delay for repeated notification if true.
     */
    public void clearPendingNotification(boolean resetRepeatTime) {
        if (resetRepeatTime) {
            mNotificationRepeatTime = 0;
        }

        if (mState != STATE_NO_NOTIFICATION) {
            getNotificationManager().cancel(SystemMessage.NOTE_NETWORK_AVAILABLE);

            if (mRecommendedNetwork != null) {
                Log.d(TAG, "Notification with state="
                        + mState
                        + " was cleared for recommended network: "
                        + mRecommendedNetwork.SSID);
            }
            mState = STATE_NO_NOTIFICATION;
            mRecommendedNetwork = null;
        }
    }

    private boolean isControllerEnabled() {
        return mSettingEnabled && !UserManager.get(mContext)
                .hasUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, UserHandle.CURRENT);
    }

    /**
     * If there are open networks, attempt to post an open network notification.
     *
     * @param availableNetworks Available networks from
     * {@link WifiNetworkSelector.NetworkEvaluator#getFilteredScanDetailsForOpenUnsavedNetworks()}.
     */
    public void handleScanResults(@NonNull List<ScanDetail> availableNetworks) {
        if (!isControllerEnabled()) {
            clearPendingNotification(true /* resetRepeatTime */);
            return;
        }
        if (availableNetworks.isEmpty()) {
            clearPendingNotification(false /* resetRepeatTime */);
            return;
        }

        // Not enough time has passed to show a recommendation notification again
        if (mState == STATE_NO_NOTIFICATION
                && mClock.getWallClockMillis() < mNotificationRepeatTime) {
            return;
        }

        // Do nothing when the screen is off and no notification is showing.
        if (mState == STATE_NO_NOTIFICATION && !mScreenOn) {
            return;
        }

        // Only show a new or update an existing recommendation notification.
        if (mState == STATE_NO_NOTIFICATION
                || mState == STATE_SHOWING_RECOMMENDATION_NOTIFICATION) {
            ScanResult recommendation = mOpenNetworkRecommender.recommendNetwork(
                    availableNetworks, new ArraySet<>(mBlacklistedSsids));

            if (recommendation != null) {
                postInitialNotification(recommendation);
            } else {
                clearPendingNotification(false /* resetRepeatTime */);
            }
        }
    }

    /** Handles screen state changes. */
    public void handleScreenStateChanged(boolean screenOn) {
        mScreenOn = screenOn;
    }

    /**
     * Called by {@link WifiConnectivityManager} when Wi-Fi is connected. If the notification
     * was in the connecting state, update the notification to show that it has connected to the
     * recommended network.
     */
    public void handleWifiConnected() {
        if (mState != STATE_CONNECTING_IN_NOTIFICATION) {
            clearPendingNotification(true /* resetRepeatTime */);
            return;
        }

        postNotification(mNotificationBuilder.createNetworkConnectedNotification(
                mRecommendedNetwork));

        Log.d(TAG, "User connected to recommended network: " + mRecommendedNetwork.SSID);
        mWifiMetrics.incrementConnectToNetworkNotification(
                ConnectToNetworkNotificationAndActionCount.NOTIFICATION_CONNECTED_TO_NETWORK);
        mState = STATE_CONNECTED_NOTIFICATION;
        mHandler.postDelayed(
                () -> {
                    if (mState == STATE_CONNECTED_NOTIFICATION) {
                        clearPendingNotification(true /* resetRepeatTime */);
                    }
                },
                TIME_TO_SHOW_CONNECTED_MILLIS);
    }

    /**
     * Handles when a Wi-Fi connection attempt failed.
     */
    public void handleConnectionFailure() {
        if (mState != STATE_CONNECTING_IN_NOTIFICATION) {
            return;
        }
        postNotification(mNotificationBuilder.createNetworkFailedNotification());

        Log.d(TAG, "User failed to connect to recommended network: " + mRecommendedNetwork.SSID);
        mWifiMetrics.incrementConnectToNetworkNotification(
                ConnectToNetworkNotificationAndActionCount.NOTIFICATION_FAILED_TO_CONNECT);
        mState = STATE_CONNECT_FAILED_NOTIFICATION;
        mHandler.postDelayed(
                () -> {
                    if (mState == STATE_CONNECT_FAILED_NOTIFICATION) {
                        clearPendingNotification(false /* resetRepeatTime */);
                    }
                },
                TIME_TO_SHOW_FAILED_MILLIS);
    }

    private NotificationManager getNotificationManager() {
        return (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private void postInitialNotification(ScanResult recommendedNetwork) {
        if (mRecommendedNetwork != null
                && TextUtils.equals(mRecommendedNetwork.SSID, recommendedNetwork.SSID)) {
            return;
        }
        postNotification(mNotificationBuilder.createConnectToNetworkNotification(
                recommendedNetwork));
        if (mState == STATE_NO_NOTIFICATION) {
            mWifiMetrics.incrementConnectToNetworkNotification(
                    ConnectToNetworkNotificationAndActionCount.NOTIFICATION_RECOMMEND_NETWORK);
        } else {
            mWifiMetrics.incrementNumOpenNetworkRecommendationUpdates();
        }
        mState = STATE_SHOWING_RECOMMENDATION_NOTIFICATION;
        mRecommendedNetwork = recommendedNetwork;
        mNotificationRepeatTime = mClock.getWallClockMillis() + mNotificationRepeatDelay;
    }

    private void postNotification(Notification notification) {
        getNotificationManager().notify(SystemMessage.NOTE_NETWORK_AVAILABLE, notification);
    }

    private void handleConnectToNetworkAction() {
        mWifiMetrics.incrementConnectToNetworkNotificationAction(mState,
                ConnectToNetworkNotificationAndActionCount.ACTION_CONNECT_TO_NETWORK);
        if (mState != STATE_SHOWING_RECOMMENDATION_NOTIFICATION) {
            return;
        }
        postNotification(mNotificationBuilder.createNetworkConnectingNotification(
                mRecommendedNetwork));
        mWifiMetrics.incrementConnectToNetworkNotification(
                ConnectToNetworkNotificationAndActionCount.NOTIFICATION_CONNECTING_TO_NETWORK);

        Log.d(TAG, "User initiated connection to recommended network: " + mRecommendedNetwork.SSID);
        WifiConfiguration network = ScanResultUtil.createNetworkFromScanResult(mRecommendedNetwork);
        Message msg = Message.obtain();
        msg.what = WifiManager.CONNECT_NETWORK;
        msg.arg1 = WifiConfiguration.INVALID_NETWORK_ID;
        msg.obj = network;
        msg.replyTo = mSrcMessenger;
        mWifiStateMachine.sendMessage(msg);

        mState = STATE_CONNECTING_IN_NOTIFICATION;
        mHandler.postDelayed(
                () -> {
                    if (mState == STATE_CONNECTING_IN_NOTIFICATION) {
                        handleConnectionFailure();
                    }
                },
                TIME_TO_SHOW_CONNECTING_MILLIS);
    }

    private void handleSeeAllNetworksAction() {
        mWifiMetrics.incrementConnectToNetworkNotificationAction(mState,
                ConnectToNetworkNotificationAndActionCount.ACTION_PICK_WIFI_NETWORK);
        startWifiSettings();
    }

    private void startWifiSettings() {
        // Close notification drawer before opening the picker.
        mContext.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        mContext.startActivity(
                new Intent(Settings.ACTION_WIFI_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        clearPendingNotification(false /* resetRepeatTime */);
    }

    private void handleConnectionAttemptFailedToSend() {
        handleConnectionFailure();
        mWifiMetrics.incrementNumOpenNetworkConnectMessageFailedToSend();
    }

    private void handlePickWifiNetworkAfterConnectFailure() {
        mWifiMetrics.incrementConnectToNetworkNotificationAction(mState,
                ConnectToNetworkNotificationAndActionCount
                        .ACTION_PICK_WIFI_NETWORK_AFTER_CONNECT_FAILURE);
        startWifiSettings();
    }

    private void handleUserDismissedAction() {
        Log.d(TAG, "User dismissed notification with state=" + mState);
        mWifiMetrics.incrementConnectToNetworkNotificationAction(mState,
                ConnectToNetworkNotificationAndActionCount.ACTION_USER_DISMISSED_NOTIFICATION);
        if (mState == STATE_SHOWING_RECOMMENDATION_NOTIFICATION) {
            // blacklist dismissed network
            mBlacklistedSsids.add(mRecommendedNetwork.SSID);
            mWifiMetrics.setOpenNetworkRecommenderBlacklistSize(mBlacklistedSsids.size());
            mConfigManager.saveToStore(false /* forceWrite */);
            Log.d(TAG, "Network is added to the open network notification blacklist: "
                    + mRecommendedNetwork.SSID);
        }
        resetStateAndDelayNotification();
    }

    private void resetStateAndDelayNotification() {
        mState = STATE_NO_NOTIFICATION;
        mNotificationRepeatTime = System.currentTimeMillis() + mNotificationRepeatDelay;
        mRecommendedNetwork = null;
    }

    /** Dump ONA controller state. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("OpenNetworkNotifier: ");
        pw.println("mSettingEnabled " + mSettingEnabled);
        pw.println("currentTime: " + mClock.getWallClockMillis());
        pw.println("mNotificationRepeatTime: " + mNotificationRepeatTime);
        pw.println("mState: " + mState);
        pw.println("mBlacklistedSsids: " + mBlacklistedSsids.toString());
    }

    private class OpenNetworkNotifierStoreData implements SsidSetStoreData.DataSource {
        @Override
        public Set<String> getSsids() {
            return new ArraySet<>(mBlacklistedSsids);
        }

        @Override
        public void setSsids(Set<String> ssidList) {
            mBlacklistedSsids.addAll(ssidList);
            mWifiMetrics.setOpenNetworkRecommenderBlacklistSize(mBlacklistedSsids.size());
        }
    }

    private class NotificationEnabledSettingObserver extends ContentObserver {
        NotificationEnabledSettingObserver(Handler handler) {
            super(handler);
        }

        public void register() {
            mFrameworkFacade.registerContentObserver(mContext, Settings.Global.getUriFor(
                    Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON), true, this);
            mSettingEnabled = getValue();
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            mSettingEnabled = getValue();
            clearPendingNotification(true /* resetRepeatTime */);
        }

        private boolean getValue() {
            boolean enabled = mFrameworkFacade.getIntegerSetting(mContext,
                    Settings.Global.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON, 1) == 1;
            mWifiMetrics.setIsWifiNetworksAvailableNotificationEnabled(enabled);
            return enabled;
        }
    }
}
