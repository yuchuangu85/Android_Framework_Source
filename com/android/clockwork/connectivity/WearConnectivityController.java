package com.android.clockwork.connectivity;

import android.annotation.Nullable;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.clockwork.bluetooth.WearBluetoothMediator;
import com.android.clockwork.cellular.WearCellularMediator;
import com.android.clockwork.common.ActivityModeTracker;
import com.android.clockwork.wifi.WearWifiMediator;

import java.util.concurrent.TimeUnit;

/**
 * WearConnectivityController routes inputs and signals from various sources
 * and relays the appropriate info to the respective WiFi/Cellular Mediators.
 *
 * The WifiMediator is expected to always exist. The BtMediator and CellMediator may be null.
 */
public class WearConnectivityController implements
        ActivityModeTracker.Listener,
        WearNetworkObserver.Listener,
        WearProxyNetworkAgent.Listener {
    private static final String TAG = "WearConnectivity";

    static final String ACTION_PROXY_STATUS_CHANGE =
            "com.android.clockwork.connectivity.action.PROXY_STATUS_CHANGE";
    static final String ACTION_NOTIFY_OFF_BODY_CHANGE =
            "com.google.android.clockwork.connectivity.action.ACTION_NOTIFY_OFF_BODY_CHANGE";

    /**
     * Specifically use a smaller state change delay when transitioning away from BT.
     * This minimizes the duration of the netTransitionWakelock held by ConnectivityService
     * whenever the primary/default network disappears, while still allowing some amount of time
     * for BT to reconnect before we enable wifi.
     *
     * See b/30574433 for more details.
     */
    private static final long DEFAULT_BT_STATE_CHANGE_DELAY_MS = TimeUnit.SECONDS.toMillis(5);
    private static final long MAX_ACCEPTABLE_DELAY_MS = TimeUnit.SECONDS.toMillis(60);

    // dependencies
    private final Context mContext;
    private final AlarmManager mAlarmManager;
    @Nullable private final WearBluetoothMediator mBtMediator;
    @Nullable private final WearCellularMediator mCellMediator;
    private final WearWifiMediator mWifiMediator;
    private final WearProxyNetworkAgent mProxyNetworkAgent;
    private final ActivityModeTracker mActivityModeTracker;

    // params
    private long mBtStateChangeDelayMs;

    // state
    private int mNumWifiRequests = 0;
    private int mNumCellularRequests;
    private int mNumHighBandwidthRequests = 0;
    private int mNumUnmeteredRequests = 0;

    @VisibleForTesting final PendingIntent notifyProxyStatusChangeIntent;
    @VisibleForTesting
    BroadcastReceiver notifyProxyStatusChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_PROXY_STATUS_CHANGE.equals(intent.getAction())) {
                notifyProxyStatusChange();
            }
        }
    };

    public WearConnectivityController(
            Context context,
            AlarmManager alarmManager,
            WearBluetoothMediator btMediator,
            WearWifiMediator wifiMediator,
            WearCellularMediator cellMediator,
            WearProxyNetworkAgent proxyNetworkAgent,
            ActivityModeTracker activityModeTracker) {
        mContext = context;
        mAlarmManager = alarmManager;
        mBtMediator = btMediator;
        mWifiMediator = wifiMediator;
        mCellMediator = cellMediator;
        mProxyNetworkAgent = proxyNetworkAgent;
        mProxyNetworkAgent.addListener(this);
        mActivityModeTracker = activityModeTracker;
        mActivityModeTracker.addListener(this);

        mBtStateChangeDelayMs = DEFAULT_BT_STATE_CHANGE_DELAY_MS;

        notifyProxyStatusChangeIntent = PendingIntent.getBroadcast(
                context, 0, new Intent(ACTION_PROXY_STATUS_CHANGE), 0);
    }

    public void onBootCompleted() {
        mContext.registerReceiver(notifyProxyStatusChangeReceiver,
                new IntentFilter(ACTION_PROXY_STATUS_CHANGE));

        if (mBtMediator != null) {
            mBtMediator.onBootCompleted();
        }
        mWifiMediator.onBootCompleted(mProxyNetworkAgent.isProxyConnected());
        if (mCellMediator != null) {
            mCellMediator.onBootCompleted(mProxyNetworkAgent.isProxyConnected());
        }

        if (mActivityModeTracker.isActivityModeEnabled()) {
            onActivityModeChanged(true);
        }
    }

    @VisibleForTesting
    void setBluetoothStateChangeDelay(long delayMs) {
        mBtStateChangeDelayMs = delayMs;
    }

    @Override
    public void onProxyConnectionChange(boolean proxyConnected) {
        mAlarmManager.cancel(notifyProxyStatusChangeIntent);

        // directly notify on connects, or if no delay is configured
        if (proxyConnected || mBtStateChangeDelayMs <= 0) {
            notifyProxyStatusChange();
        } else {
            mAlarmManager.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + mBtStateChangeDelayMs,
                    MAX_ACCEPTABLE_DELAY_MS,
                    notifyProxyStatusChangeIntent);
        }
    }

    private void notifyProxyStatusChange() {
        mWifiMediator.updateProxyConnected(mProxyNetworkAgent.isProxyConnected());
        if (mCellMediator != null) {
            mCellMediator.updateProxyConnected(mProxyNetworkAgent.isProxyConnected());
        }
    }

    @Override
    public void onUnmeteredRequestsChanged(int numUnmeteredRequests) {
        mNumUnmeteredRequests = numUnmeteredRequests;
        mWifiMediator.updateNumUnmeteredRequests(numUnmeteredRequests);
    }

    @Override
    public void onHighBandwidthRequestsChanged(int numHighBandwidthRequests) {
        mNumHighBandwidthRequests = numHighBandwidthRequests;
        if (mCellMediator != null) {
            mCellMediator.updateNumHighBandwidthRequests(numHighBandwidthRequests);
        }
        mWifiMediator.updateNumHighBandwidthRequests(numHighBandwidthRequests);
    }

    @Override
    public void onWifiRequestsChanged(int numWifiRequests) {
        mNumWifiRequests = numWifiRequests;
        mWifiMediator.updateNumWifiRequests(numWifiRequests);
    }

    @Override
    public void onCellularRequestsChanged(int numCellularRequests) {
        mNumCellularRequests = numCellularRequests;
        if (mCellMediator != null) {
            mCellMediator.updateNumCellularRequests(numCellularRequests);
        }
    }

    @Override
    public void onActivityModeChanged(boolean enabled) {
        if (mActivityModeTracker.affectsBluetooth()) {
            mBtMediator.updateActivityMode(enabled);
        }

        if (mActivityModeTracker.affectsWifi()) {
            mWifiMediator.updateActivityMode(enabled);
        }

        if (mActivityModeTracker.affectsCellular()) {
            mCellMediator.updateActivityMode(enabled);
        }
    }

    public void dump(IndentingPrintWriter ipw) {
        ipw.println("================ WearConnectivityService ================");
        ipw.println("Proxy NetworkAgent connection status:" +
                (mProxyNetworkAgent.isProxyConnected() ? "connected" : "disconnected"));
        mActivityModeTracker.dump(ipw);
        ipw.println();
        ipw.printPair("mNumHighBandwidthRequests", mNumHighBandwidthRequests);
        ipw.printPair("mNumUnmeteredRequests", mNumUnmeteredRequests);
        ipw.printPair("mNumWifiRequests", mNumWifiRequests);
        ipw.printPair("mNumCellularRequests", mNumCellularRequests);
        ipw.println();

        ipw.increaseIndent();

        ipw.println();
        if (mBtMediator != null) {
            mBtMediator.dump(ipw);
        } else {
            ipw.println("Wear Bluetooth disabled because BluetoothAdapter is missing.");
        }

        ipw.println();
        mWifiMediator.dump(ipw);

        ipw.println();
        if (mCellMediator != null) {
            mCellMediator.dump(ipw);
        } else {
            ipw.println("Wear Cellular Mediator disabled on this device.");
        }
        ipw.println();
        ipw.decreaseIndent();
    }
}
