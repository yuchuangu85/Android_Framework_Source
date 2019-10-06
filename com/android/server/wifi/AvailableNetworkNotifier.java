/*
 * Copyright (C) 2018 The Android Open Source Project
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
import static com.android.server.wifi.ConnectToNetworkNotificationBuilder.AVAILABLE_NETWORK_NOTIFIER_TAG;

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
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.nano.WifiMetricsProto.ConnectToNetworkNotificationAndActionCount;
import com.android.server.wifi.util.ScanResultUtil;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;

/**
 * Base class for all network notifiers (e.g. OpenNetworkNotifier, CarrierNetworkNotifier).
 *
 * NOTE: These API's are not thread safe and should only be used from WifiCoreThread.
 */
public class AvailableNetworkNotifier {

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
    /** The initial notification recommending a network to connect to is shown. */
    private static final int STATE_SHOWING_RECOMMENDATION_NOTIFICATION = 1;
    /** The notification of status of connecting to the recommended network is shown. */
    private static final int STATE_CONNECTING_IN_NOTIFICATION = 2;
    /** The notification that the connection to the recommended network was successful is shown. */
    private static final int STATE_CONNECTED_NOTIFICATION = 3;
    /** The notification to show that connection to the recommended network failed is shown. */
    private static final int STATE_CONNECT_FAILED_NOTIFICATION = 4;

    /** Current state of the notification. */
    @State private int mState = STATE_NO_NOTIFICATION;

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
    private final Set<String> mBlacklistedSsids = new ArraySet<>();

    private final Context mContext;
    private final Handler mHandler;
    private final FrameworkFacade mFrameworkFacade;
    private final WifiMetrics mWifiMetrics;
    private final Clock mClock;
    private final WifiConfigManager mConfigManager;
    private final ClientModeImpl mClientModeImpl;
    private final Messenger mSrcMessenger;
    private final ConnectToNetworkNotificationBuilder mNotificationBuilder;

    private ScanResult mRecommendedNetwork;

    /** Tag used for logs and metrics */
    private final String mTag;
    /** Identifier of the {@link SsidSetStoreData}. */
    private final String mStoreDataIdentifier;
    /** Identifier for the settings toggle, used for registering ContentObserver */
    private final String mToggleSettingsName;

    /** System wide identifier for notification in Notification Manager */
    private final int mSystemMessageNotificationId;

    /**
     * The nominator id for this class, from
     * {@link com.android.server.wifi.nano.WifiMetricsProto.ConnectionEvent.ConnectionNominator}
     */
    private final int mNominatorId;

    public AvailableNetworkNotifier(
            String tag,
            String storeDataIdentifier,
            String toggleSettingsName,
            int notificationIdentifier,
            int nominatorId,
            Context context,
            Looper looper,
            FrameworkFacade framework,
            Clock clock,
            WifiMetrics wifiMetrics,
            WifiConfigManager wifiConfigManager,
            WifiConfigStore wifiConfigStore,
            ClientModeImpl clientModeImpl,
            ConnectToNetworkNotificationBuilder connectToNetworkNotificationBuilder) {
        mTag = tag;
        mStoreDataIdentifier = storeDataIdentifier;
        mToggleSettingsName = toggleSettingsName;
        mSystemMessageNotificationId = notificationIdentifier;
        mNominatorId = nominatorId;
        mContext = context;
        mHandler = new Handler(looper);
        mFrameworkFacade = framework;
        mWifiMetrics = wifiMetrics;
        mClock = clock;
        mConfigManager = wifiConfigManager;
        mClientModeImpl = clientModeImpl;
        mNotificationBuilder = connectToNetworkNotificationBuilder;
        mScreenOn = false;
        mSrcMessenger = new Messenger(new Handler(looper, mConnectionStateCallback));
        wifiConfigStore.registerStoreData(new SsidSetStoreData(mStoreDataIdentifier,
                new AvailableNetworkNotifierStoreData()));

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
                    if (!mTag.equals(intent.getExtra(AVAILABLE_NETWORK_NOTIFIER_TAG))) {
                        return;
                    }
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
                            Log.e(mTag, "Unknown action " + intent.getAction());
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
                Log.e("AvailableNetworkNotifier", "Unknown message " + msg.what);
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
            getNotificationManager().cancel(mSystemMessageNotificationId);

            if (mRecommendedNetwork != null) {
                Log.d(mTag, "Notification with state="
                        + mState
                        + " was cleared for recommended network: "
                        + "\"" + mRecommendedNetwork.SSID + "\"");
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
     * If there are available networks, attempt to post a network notification.
     *
     * @param availableNetworks Available networks to choose from and possibly show notification
     */
    public void handleScanResults(@NonNull List<ScanDetail> availableNetworks) {
        if (!isControllerEnabled()) {
            clearPendingNotification(true /* resetRepeatTime */);
            return;
        }
        if (availableNetworks.isEmpty() && mState == STATE_SHOWING_RECOMMENDATION_NOTIFICATION) {
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
            ScanResult recommendation =
                    recommendNetwork(availableNetworks);

            if (recommendation != null) {
                postInitialNotification(recommendation);
            } else {
                clearPendingNotification(false /* resetRepeatTime */);
            }
        }
    }

    /**
     * Recommends a network to connect to from a list of available networks, while ignoring the
     * SSIDs in the blacklist.
     *
     * @param networks List of networks to select from
     */
    public ScanResult recommendNetwork(@NonNull List<ScanDetail> networks) {
        ScanResult result = null;
        int highestRssi = Integer.MIN_VALUE;
        for (ScanDetail scanDetail : networks) {
            ScanResult scanResult = scanDetail.getScanResult();

            if (scanResult.level > highestRssi) {
                result = scanResult;
                highestRssi = scanResult.level;
            }
        }

        if (result != null && mBlacklistedSsids.contains(result.SSID)) {
            result = null;
        }
        return result;
    }

    /** Handles screen state changes. */
    public void handleScreenStateChanged(boolean screenOn) {
        mScreenOn = screenOn;
    }

    /**
     * Called by {@link WifiConnectivityManager} when Wi-Fi is connected. If the notification
     * was in the connecting state, update the notification to show that it has connected to the
     * recommended network.
     *
     * @param ssid The connected network's ssid
     */
    public void handleWifiConnected(String ssid) {
        removeNetworkFromBlacklist(ssid);
        if (mState != STATE_CONNECTING_IN_NOTIFICATION) {
            clearPendingNotification(true /* resetRepeatTime */);
            return;
        }

        postNotification(mNotificationBuilder.createNetworkConnectedNotification(mTag,
                mRecommendedNetwork));

        Log.d(mTag, "User connected to recommended network: "
                + "\"" + mRecommendedNetwork.SSID + "\"");
        mWifiMetrics.incrementConnectToNetworkNotification(mTag,
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
        postNotification(mNotificationBuilder.createNetworkFailedNotification(mTag));

        Log.d(mTag, "User failed to connect to recommended network: "
                + "\"" + mRecommendedNetwork.SSID + "\"");
        mWifiMetrics.incrementConnectToNetworkNotification(mTag,
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

        postNotification(mNotificationBuilder.createConnectToAvailableNetworkNotification(mTag,
                recommendedNetwork));

        if (mState == STATE_NO_NOTIFICATION) {
            mWifiMetrics.incrementConnectToNetworkNotification(mTag,
                    ConnectToNetworkNotificationAndActionCount.NOTIFICATION_RECOMMEND_NETWORK);
        } else {
            mWifiMetrics.incrementNumNetworkRecommendationUpdates(mTag);
        }
        mState = STATE_SHOWING_RECOMMENDATION_NOTIFICATION;
        mRecommendedNetwork = recommendedNetwork;
        mNotificationRepeatTime = mClock.getWallClockMillis() + mNotificationRepeatDelay;
    }

    private void postNotification(Notification notification) {
        getNotificationManager().notify(mSystemMessageNotificationId, notification);
    }

    private void handleConnectToNetworkAction() {
        mWifiMetrics.incrementConnectToNetworkNotificationAction(mTag, mState,
                ConnectToNetworkNotificationAndActionCount.ACTION_CONNECT_TO_NETWORK);
        if (mState != STATE_SHOWING_RECOMMENDATION_NOTIFICATION) {
            return;
        }
        postNotification(mNotificationBuilder.createNetworkConnectingNotification(mTag,
                mRecommendedNetwork));
        mWifiMetrics.incrementConnectToNetworkNotification(mTag,
                ConnectToNetworkNotificationAndActionCount.NOTIFICATION_CONNECTING_TO_NETWORK);

        Log.d(mTag,
                "User initiated connection to recommended network: "
                        + "\"" + mRecommendedNetwork.SSID + "\"");
        WifiConfiguration network = createRecommendedNetworkConfig(mRecommendedNetwork);

        NetworkUpdateResult result = mConfigManager.addOrUpdateNetwork(network, Process.WIFI_UID);
        if (result.isSuccess()) {
            mWifiMetrics.setNominatorForNetwork(result.netId, mNominatorId);

            Message msg = Message.obtain();
            msg.what = WifiManager.CONNECT_NETWORK;
            msg.arg1 = result.netId;
            msg.obj = null;
            msg.replyTo = mSrcMessenger;
            mClientModeImpl.sendMessage(msg);
            addNetworkToBlacklist(mRecommendedNetwork.SSID);
        }

        mState = STATE_CONNECTING_IN_NOTIFICATION;
        mHandler.postDelayed(
                () -> {
                    if (mState == STATE_CONNECTING_IN_NOTIFICATION) {
                        handleConnectionFailure();
                    }
                },
                TIME_TO_SHOW_CONNECTING_MILLIS);
    }

    private void addNetworkToBlacklist(String ssid) {
        mBlacklistedSsids.add(ssid);
        mWifiMetrics.setNetworkRecommenderBlacklistSize(mTag, mBlacklistedSsids.size());
        mConfigManager.saveToStore(false /* forceWrite */);
        Log.d(mTag, "Network is added to the network notification blacklist: "
                + "\"" + ssid + "\"");
    }

    private void removeNetworkFromBlacklist(String ssid) {
        if (ssid == null) {
            return;
        }
        if (!mBlacklistedSsids.remove(ssid)) {
            return;
        }
        mWifiMetrics.setNetworkRecommenderBlacklistSize(mTag, mBlacklistedSsids.size());
        mConfigManager.saveToStore(false /* forceWrite */);
        Log.d(mTag, "Network is removed from the network notification blacklist: "
                + "\"" + ssid + "\"");
    }

    WifiConfiguration createRecommendedNetworkConfig(ScanResult recommendedNetwork) {
        return ScanResultUtil.createNetworkFromScanResult(recommendedNetwork);
    }

    private void handleSeeAllNetworksAction() {
        mWifiMetrics.incrementConnectToNetworkNotificationAction(mTag, mState,
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
        mWifiMetrics.incrementNumNetworkConnectMessageFailedToSend(mTag);
    }

    private void handlePickWifiNetworkAfterConnectFailure() {
        mWifiMetrics.incrementConnectToNetworkNotificationAction(mTag, mState,
                ConnectToNetworkNotificationAndActionCount
                        .ACTION_PICK_WIFI_NETWORK_AFTER_CONNECT_FAILURE);
        startWifiSettings();
    }

    private void handleUserDismissedAction() {
        Log.d(mTag, "User dismissed notification with state=" + mState);
        mWifiMetrics.incrementConnectToNetworkNotificationAction(mTag, mState,
                ConnectToNetworkNotificationAndActionCount.ACTION_USER_DISMISSED_NOTIFICATION);
        if (mState == STATE_SHOWING_RECOMMENDATION_NOTIFICATION) {
            // blacklist dismissed network
            addNetworkToBlacklist(mRecommendedNetwork.SSID);
        }
        resetStateAndDelayNotification();
    }

    private void resetStateAndDelayNotification() {
        mState = STATE_NO_NOTIFICATION;
        mNotificationRepeatTime = System.currentTimeMillis() + mNotificationRepeatDelay;
        mRecommendedNetwork = null;
    }

    /** Dump this network notifier's state. */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(mTag + ": ");
        pw.println("mSettingEnabled " + mSettingEnabled);
        pw.println("currentTime: " + mClock.getWallClockMillis());
        pw.println("mNotificationRepeatTime: " + mNotificationRepeatTime);
        pw.println("mState: " + mState);
        pw.println("mBlacklistedSsids: " + mBlacklistedSsids.toString());
    }

    private class AvailableNetworkNotifierStoreData implements SsidSetStoreData.DataSource {
        @Override
        public Set<String> getSsids() {
            return new ArraySet<>(mBlacklistedSsids);
        }

        @Override
        public void setSsids(Set<String> ssidList) {
            mBlacklistedSsids.addAll(ssidList);
            mWifiMetrics.setNetworkRecommenderBlacklistSize(mTag, mBlacklistedSsids.size());
        }
    }

    private class NotificationEnabledSettingObserver extends ContentObserver {
        NotificationEnabledSettingObserver(Handler handler) {
            super(handler);
        }

        public void register() {
            mFrameworkFacade.registerContentObserver(mContext,
                    Settings.Global.getUriFor(mToggleSettingsName), true, this);
            mSettingEnabled = getValue();
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            mSettingEnabled = getValue();
            clearPendingNotification(true /* resetRepeatTime */);
        }

        private boolean getValue() {
            boolean enabled =
                    mFrameworkFacade.getIntegerSetting(mContext, mToggleSettingsName, 1) == 1;
            mWifiMetrics.setIsWifiNetworksAvailableNotificationEnabled(mTag, enabled);
            Log.d(mTag, "Settings toggle enabled=" + enabled);
            return enabled;
        }
    }
}
