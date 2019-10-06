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

package com.android.clockwork.wifi;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.android.clockwork.bluetooth.CompanionTracker;
import com.android.clockwork.common.EventHistory;
import com.android.clockwork.common.PartialWakeLock;
import com.android.clockwork.common.RadioToggler;
import com.android.clockwork.flags.BooleanFlag;
import com.android.clockwork.power.PowerTracker;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.util.concurrent.TimeUnit;

import static com.android.clockwork.wifi.WearWifiMediatorSettings.WIFI_SETTING_ON;

/**
 * Wi-Fi Mediator responds to these signals:
 * - Whether device is charging
 * - Whether wifi has been requested by another app
 * - Whether device is connected to bluetooth
 * - Whether the device has any configured networks to connect to
 *
 * Details: go/cw-wifi-state-management-f  go/cw-wifi-settings-management-f
 */
public class WearWifiMediator implements
        PowerTracker.Listener,
        WearWifiMediatorSettings.Listener,
        WifiBackoff.Listener {
    private static final String TAG = "WearWifiMediator";

    static final String ACTION_EXIT_WIFI_LINGER =
            "com.android.clockwork.connectivity.action.ACTION_EXIT_WIFI_LINGER";

    /**
     * Enforces a delay every time we decide to turn WiFi off.
     * Prevents WiFi from thrashing when we very quickly transition between desired wifi states.
     *
     * We use AlarmManager to enforce the turning off of WiFi after the linger period.
     * The constants below define the default linger window to be 30-60 seconds.
     */
    private static final long DEFAULT_WIFI_LINGER_DURATION_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long MAX_ACCEPTABLE_LINGER_DELAY_MS = TimeUnit.SECONDS.toMillis(30);

    /**
     * How long should we wait between calls to enabling/disabling the WifiAdapter.
     */
    private static final long WAIT_FOR_RADIO_TOGGLE_DELAY_MS = TimeUnit.SECONDS.toMillis(2);

    // dependencies
    private final Context mContext;
    private final AlarmManager mAlarmManager;
    private final WearWifiMediatorSettings mSettings;
    private final CompanionTracker mCompanionTracker;
    private final PowerTracker mPowerTracker;

    private final BooleanFlag mUserAbsentRadiosOff;

    private final WifiBackoff mWifiBackoff;
    private final WifiManager mWifiManager;

    private RadioToggler mRadioToggler;

    // params
    private long mWifiLingerDurationMs;

    // state
    private boolean mInitialized = false;
    private String mWifiSetting;
    private boolean mInWifiSettings = false;
    private boolean mEnableWifiWhenCharging;

    private boolean mWifiDesired;
    private boolean mWifiConnected = false;
    private boolean mWifiLingering = false;

    private boolean mInHardwareLowPowerMode;
    private boolean mIsProxyConnected;

    private int mNumUnmeteredRequests = 0;
    private int mNumHighBandwidthRequests = 0;
    private int mNumWifiRequests = 0;

    private boolean mActivityMode = false;

    private int mNumConfiguredNetworks = 0;
    private boolean mDisableWifiMediator = false;

    private boolean mWifiOnWhenProxyDisconnected = true;

    private final EventHistory<WifiDecision> mDecisionHistory =
            new EventHistory<>("Wifi Decision History", 30, false);

    private final WifiLogger mWifiLogger;

    private enum WifiDecisionReason {
        OFF_ACTIVITY_MODE,
        OFF_HARDWARE_LOW_POWER,
        OFF_NO_CONFIGURED_NETWORKS,
        OFF_NO_REQUESTS,
        OFF_POWER_SAVE,
        OFF_USER_ABSENT,
        OFF_WIFI_BACKOFF,
        OFF_WIFI_SETTING_OFF,
        ON_CHARGING,
        ON_IN_WIFI_SETTINGS,
        ON_NETWORK_REQUEST,
        ON_PROXY_DISCONNECTED,
    }

    public class WifiDecision extends EventHistory.Event {
        public final WifiDecisionReason reason;

        public WifiDecision(WifiDecisionReason reason) {
            this.reason = reason;
        }

        @Override
        public String getName() {
            return reason.name();
        }
    }

    @VisibleForTesting final PendingIntent exitWifiLingerIntent;
    @VisibleForTesting
    BroadcastReceiver exitWifiLingerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_EXIT_WIFI_LINGER.equals(intent.getAction())) {
                if (mWifiLingering) {
                    mWifiLingering = false;
                    mRadioToggler.toggleRadio(false);
                }
            }
        }
    };

    public WearWifiMediator(Context context,
                            AlarmManager alarmManager,
                            WearWifiMediatorSettings wifiMediatorSettings,
                            CompanionTracker companionTracker,
                            PowerTracker powerTracker,
                            BooleanFlag userAbsentRadiosOff,
                            WifiBackoff wifiBackoff,
                            WifiManager wifiManager,
                            WifiLogger wifiLogger) {
        mContext = context;
        mAlarmManager = alarmManager;
        mSettings = wifiMediatorSettings;
        mSettings.addListener(this);
        mCompanionTracker = companionTracker;
        mPowerTracker = powerTracker;
        mPowerTracker.addListener(this);

        mUserAbsentRadiosOff = userAbsentRadiosOff;
        mUserAbsentRadiosOff.addListener(this::onUserAbsentRadiosOffChanged);

        mWifiBackoff = wifiBackoff;
        mWifiBackoff.setListener(this);
        mWifiManager = wifiManager;
        mWifiLogger = wifiLogger;

        mWifiLingerDurationMs = DEFAULT_WIFI_LINGER_DURATION_MS;
        context.registerReceiver(mReceiver, getBroadcastReceiverIntentFilter());

        exitWifiLingerIntent = PendingIntent.getBroadcast(
                context, 0, new Intent(ACTION_EXIT_WIFI_LINGER), 0);

        RadioToggler.Radio wifiRadio = new RadioToggler.Radio() {
            @Override
            public String logTag() {
                return TAG;
            }

            @Override
            public void setEnabled(boolean enabled) {
                mWifiManager.setWifiEnabled(enabled);
            }

            @Override
            public boolean getEnabled() {
                return mWifiManager.getWifiState() == WifiManager.WIFI_STATE_ENABLED;
            }
        };

        mRadioToggler = new RadioToggler(wifiRadio,
                new PartialWakeLock(mContext,"WifiMediator"), WAIT_FOR_RADIO_TOGGLE_DELAY_MS);
    }

    @VisibleForTesting
    void overrideRadioTogglerForTest(RadioToggler radioToggler) {
        mRadioToggler = radioToggler;
    }

    public void onBootCompleted(boolean proxyConnected) {
        mContext.registerReceiver(exitWifiLingerReceiver,
                new IntentFilter(ACTION_EXIT_WIFI_LINGER));

        mIsProxyConnected = proxyConnected;

        mWifiSetting = mSettings.getWifiSetting();
        mEnableWifiWhenCharging = mSettings.getEnableWifiWhileCharging();
        mDisableWifiMediator = mSettings.getDisableWifiMediator();
        mInHardwareLowPowerMode = mSettings.getHardwareLowPowerMode();

        mRadioToggler.refreshRadioState();

        refreshNumConfiguredNetworks();

        mWifiOnWhenProxyDisconnected =
                mSettings.getWifiOnWhenProxyDisconnected();

        // b/66052197 -- sometimes WifiManager returns an empty list of networks if
        // we call getConfiguredNetworks too soon after boot.  This ensures that we
        // check one more time before deciding that the device has no configured networks.
        // This code should be removed when the WifiManager bug is fixed.
        if (mNumConfiguredNetworks == 0) {
            mWifiNetworksWorkaroundHandler = new Handler();
            mWifiNetworksWorkaroundHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "Re-checking configured networks .");
                    refreshNumConfiguredNetworks();
                    mWifiNetworksWorkaroundHandler = null;
                }
            }, WIFI_NETWORK_WORKAROUND_DELAY_MS);
        }

        mInitialized = true;
        updateWifiState("onBootCompleted");
    }

    private Handler mWifiNetworksWorkaroundHandler;

    // b/66052197 - the length of time after onBootComplete to retry fetching
    // configured networks from WifiManager
    private final long WIFI_NETWORK_WORKAROUND_DELAY_MS = TimeUnit.SECONDS.toMillis(30);

    @VisibleForTesting
    void setWifiLingerDuration(long durationMs) {
        mWifiLingerDurationMs = durationMs;
    }

    /**
     * Do NOT use this method outside of testing!
     */
    @VisibleForTesting
    void setNumConfiguredNetworks(int numConfiguredNetworks) {
        mNumConfiguredNetworks = numConfiguredNetworks;
    }

    public void updateProxyConnected(boolean proxyConnected) {
        mIsProxyConnected = proxyConnected;
        updateWifiState("proxyConnected changed: " + mIsProxyConnected);
    }

    public void updateNumUnmeteredRequests(int numUnmeteredRequests) {
        mNumUnmeteredRequests = numUnmeteredRequests;
        updateWifiState("numUnmeteredRequests changed: " + mNumUnmeteredRequests);
    }

    public void updateNumHighBandwidthRequests(int numHighBandwidthRequests) {
        mNumHighBandwidthRequests = numHighBandwidthRequests;
        updateWifiState("numHighBandwidthRequests changed: " + mNumHighBandwidthRequests);
    }

    public void updateNumWifiRequests(int numWifiRequests) {
        mNumWifiRequests = numWifiRequests;
        updateWifiState("numWifiRequests changed: " + mNumWifiRequests);
    }

    public void updateActivityMode(boolean activityMode) {
        if (mActivityMode != activityMode) {
            mActivityMode = activityMode;
            updateWifiState("activity mode changed: " + mActivityMode);
        }
    }

    public void onUserAbsentRadiosOffChanged(boolean isEnabled) {
        updateWifiState("UserAbsentRadiosOff flag changed: " + isEnabled);
    }

    private void refreshNumConfiguredNetworks() {
        mNumConfiguredNetworks = mWifiManager.getConfiguredNetworks().size();
        Log.d(TAG, "mNumConfiguredNetworks refreshed to: " + mNumConfiguredNetworks);
    }

    /**
     * PowerTracker.Listener callbacks
     */

    @Override
    public void onPowerSaveModeChanged() {
        updateWifiState("PowerSaveMode changed: " + mPowerTracker.isInPowerSave());
    }

    @Override
    public void onChargingStateChanged() {
        updateWifiState("ChargingState changed: " + mPowerTracker.isCharging());
    }

    @Override
    public void onDeviceIdleModeChanged() {
        updateWifiState("DeviceIdleMode changed: " + mPowerTracker.isDeviceIdle());
    }

    /**
     * WearWifiMediatorSettings.Listener callbacks
     */

    @Override
    public void onWifiSettingChanged(String newWifiSetting) {
        mWifiSetting = newWifiSetting;
        updateWifiState("wifiSetting changed: " + mWifiSetting);
    }

    @Override
    public void onInWifiSettingsMenuChanged(boolean inWifiSettingsMenu) {
        mInWifiSettings = inWifiSettingsMenu;
        updateWifiState("inWifiSettingsMenu changed: " + mInWifiSettings);
    }

    @Override
    public void onEnableWifiWhileChargingChanged(boolean enableWifiWhileCharging) {
        mEnableWifiWhenCharging = enableWifiWhileCharging;
        updateWifiState("enableWifiWhileCharging changed: " + mEnableWifiWhenCharging);
    }

    @Override
    public void onDisableWifiMediatorChanged(boolean disableWifiMediator) {
        mDisableWifiMediator = disableWifiMediator;
        updateWifiState("disableWifiMediator changed: " + mDisableWifiMediator);
    }

    @Override
    public void onHardwareLowPowerModeChanged(boolean inHardwareLowPowerMode) {
        mInHardwareLowPowerMode = inHardwareLowPowerMode;
        updateWifiState("inHardwareLowPowerMode changed: " + mInHardwareLowPowerMode);
    }

    @Override
    public void onWifiOnWhenProxyDisconnectedChanged(boolean wifiOnWhenProxyDisconnected) {
        mWifiOnWhenProxyDisconnected = wifiOnWhenProxyDisconnected;
        updateWifiState("wifiOnWhenProxyDisconnected changed: " + mWifiOnWhenProxyDisconnected);
    }

    /**
     * WifiBackoff.Listener callbacks
     */

    @Override
    public void onWifiBackoffChanged() {
        updateWifiState("onWifiBackoffChanged: " + mWifiBackoff.isInBackoff());
    }

    /**
     * Turn on or off wifi based on state.
     *
     * Be very careful when adding a new rule!  The order in which these rules are laid out
     * defines their priority and conditionality.  Each rule is subject to the conditions of
     * all the rules above it, but not vice versa.
     */
    private void updateWifiState(String reason) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "updateWifiState [" + reason + "]");
        }
        if (!mInitialized) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "updateWifiState [" + reason + "] ignored because"
                        + " WifiMediator is not yet initialized");
            }
            return;
        }

        if (mInHardwareLowPowerMode) {
            setWifiDecision(false, WifiDecisionReason.OFF_HARDWARE_LOW_POWER);
        } else if (mDisableWifiMediator) {
            return;
        } else if (!WIFI_SETTING_ON.equals(mWifiSetting)) {
            // WIFI_SETTING_OFF/OFF_AIRPLANE unconditionally keeps Wi-Fi off.
            setWifiDecision(false, WifiDecisionReason.OFF_WIFI_SETTING_OFF);
        } else if (mInWifiSettings) {
            // If WIFI_SETTING_ON and in Wifi Settings, we should always turn on wifi.
            setWifiDecision(true, WifiDecisionReason.ON_IN_WIFI_SETTINGS);
        } else if (mPowerTracker.isCharging() && mEnableWifiWhenCharging) {
            setWifiDecision(true, WifiDecisionReason.ON_CHARGING);
        } else if (mPowerTracker.isInPowerSave()) {
            setWifiDecision(false, WifiDecisionReason.OFF_POWER_SAVE);
        } else if (mActivityMode) {
            setWifiDecision(false, WifiDecisionReason.OFF_ACTIVITY_MODE);
        } else if (mPowerTracker.isDeviceIdle() && mUserAbsentRadiosOff.isEnabled()) {
            setWifiDecision(false, WifiDecisionReason.OFF_USER_ABSENT);
        } else if (mWifiBackoff.isInBackoff()) {
            // All rules past this one are subject to Wifi Backoff.
            setWifiDecision(false, WifiDecisionReason.OFF_WIFI_BACKOFF);
        } else if (mNumHighBandwidthRequests > 0) {
            setWifiDecision(true, WifiDecisionReason.ON_NETWORK_REQUEST);
        } else if (mNumUnmeteredRequests > 0 && mCompanionTracker.isCompanionBle()) {
            setWifiDecision(true, WifiDecisionReason.ON_NETWORK_REQUEST);
        } else if (mNumWifiRequests > 0) {
            setWifiDecision(true, WifiDecisionReason.ON_NETWORK_REQUEST);
        } else if (mNumConfiguredNetworks == 0) {
            setWifiDecision(false, WifiDecisionReason.OFF_NO_CONFIGURED_NETWORKS);
        } else if(mWifiOnWhenProxyDisconnected && !mIsProxyConnected) {
            setWifiDecision(true, WifiDecisionReason.ON_PROXY_DISCONNECTED);
        } else {
            setWifiDecision(false, WifiDecisionReason.OFF_NO_REQUESTS);
        }

        refreshBackoffStatus();
    }

    private void setWifiDecision(boolean wifiDesired, WifiDecisionReason reason) {
        mWifiDesired = wifiDesired;
        mDecisionHistory.recordEvent(new WifiDecision(reason));

        Log.d(TAG, "setWifiDecision: " + mWifiDesired + "; reason: " + reason);
        if (mWifiDesired) {
            mAlarmManager.cancel(exitWifiLingerIntent);
            mWifiLingering = false;
            mRadioToggler.toggleRadio(mWifiDesired);
        } else if (shouldSkipWifiLingering(reason)) {
            // if wifi lingering is not configured or if the user is actively turning off wifi,
            // do it right away
            mRadioToggler.toggleRadio(false);
        } else {
            // for all other reasons for disabling wifi, linger before turning it off
            // unless a previous call to updateWifiState already set the linger state
            if (!mWifiLingering) {
                mAlarmManager.cancel(exitWifiLingerIntent);
                mWifiLingering = true;
                mAlarmManager.setWindow(AlarmManager.ELAPSED_REALTIME,
                        SystemClock.elapsedRealtime() + mWifiLingerDurationMs,
                        MAX_ACCEPTABLE_LINGER_DELAY_MS,
                        exitWifiLingerIntent);
            }
        }
    }

    /**
     * This method codifies the cases where we should bypass WiFi lingering and directly
     * shut off WiFi when the decision to do so is made.
     */
    private boolean shouldSkipWifiLingering(WifiDecisionReason reason) {
        return mWifiLingerDurationMs <= 0
                || WifiDecisionReason.OFF_WIFI_SETTING_OFF.equals(reason)
                || WifiDecisionReason.OFF_POWER_SAVE.equals(reason)
                || WifiDecisionReason.OFF_HARDWARE_LOW_POWER.equals(reason);
    }

    /**
     * We need to keep track of the signals that ConnectivityService uses to determine whether
     * to connect or disconnect wifi, and correlate that information with any changes in wifi
     * state.  This allows us to distinguish the cases when wifi is disconnected because we are
     * not within range of an AP, or if wifi is disconnected simply because ConnectivityService
     * deems it no longer necessary to connect to wifi.
     *
     * Having this distinction is necessary for scheduling backoff during appropriate times.
     */
    private boolean connectivityServiceWantsWifi() {
        return !mIsProxyConnected
                || (mNumUnmeteredRequests > 0 && mCompanionTracker.isCompanionBle())
                || (mNumHighBandwidthRequests > 0)
                || (mNumWifiRequests > 0);
    }

    /**
     * This is a pretty conservative approach to deciding when to allow the device to enter or
     * stay in Wifi Backoff.  Basically, we'll only really go into backoff if something attempts
     * to hold wifi up for a very long period of time and is unsuccessful in connecting.
     *
     * We will exit backoff the moment wifi is connected or the need to connect to wifi disappears,
     * so this addresses the primary backoff use case (proxy disconnected outside of wifi range)
     * while minimally affecting wifi behavior in all other scenarios.
     */
    private void refreshBackoffStatus() {
        if (!mWifiConnected && connectivityServiceWantsWifi() && !mPowerTracker.isCharging()) {
            mWifiBackoff.scheduleBackoff();
        } else {
            mWifiBackoff.cancelBackoff();
        }

        // any time we need to check for wifi backoff is a good time to update our current wifi status
        mWifiLogger.recordWifiState(mRadioToggler.getRadioEnabled(), mWifiConnected, connectivityServiceWantsWifi());
    }

    @VisibleForTesting
    IntentFilter getBroadcastReceiverIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        return intentFilter;
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION:
                    boolean multipleNetworksChanged = intent.getBooleanExtra(
                            WifiManager.EXTRA_MULTIPLE_NETWORKS_CHANGED, false);
                    if (multipleNetworksChanged) {
                        refreshNumConfiguredNetworks();
                    } else {
                        int changeReason = intent.getIntExtra(WifiManager.EXTRA_CHANGE_REASON, -1);
                        if (changeReason == WifiManager.CHANGE_REASON_ADDED) {
                            mNumConfiguredNetworks++;
                        } else if (changeReason == WifiManager.CHANGE_REASON_REMOVED) {
                            mNumConfiguredNetworks--;
                        }

                        // This should basically never happen, but in case something goes wrong...
                        if (mNumConfiguredNetworks < 0) {
                            Log.w(TAG, "Configured networks is less than 0. Doing a full refresh");
                            refreshNumConfiguredNetworks();
                        }
                    }
                    updateWifiState("mNumConfiguredNetworks changed: " + mNumConfiguredNetworks);
                    break;
                case WifiManager.NETWORK_STATE_CHANGED_ACTION:
                    NetworkInfo networkInfo = intent.getParcelableExtra(
                            WifiManager.EXTRA_NETWORK_INFO);
                    boolean prevConnectedState = mWifiConnected;
                    Log.d(TAG, "NETWORK_STATE_CHANGED : "
                            + (mWifiConnected ? "Connected" : "Disconnected"));
                    mWifiConnected = networkInfo.isConnected();
                    if (prevConnectedState != mWifiConnected) {
                        refreshBackoffStatus();
                    }
                    break;
                case WifiManager.WIFI_STATE_CHANGED_ACTION:
                    // WifiMediator needs to monitor WiFi state in case another app directly
                    // manipulates the WiFi adapter by calling WifiManager.setWifiEnabled.
                    // The trick here is to distinguish changes to the adapter state that are
                    // a result of WifiMediator's own decisions, vs. changes to the adapter state
                    // from an outside app calling into WifiManager.
                    // see b/34360714 for more background/details
                    int wifiState = intent.getIntExtra(
                            WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);

                    if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
                        mRadioToggler.refreshRadioState();
                        if (WIFI_SETTING_ON.equals(mWifiSetting)) {
                            // If WiFi is already set to ON, then WiFi may have been turned
                            // on by WifiMediator itself.  We should only force an update if
                            // mWifiDesired is false in this case.
                            if (!mWifiDesired) {
                                Log.d(TAG, "Wifi is ON but mWifiDesired is FALSE. Updating state...");
                                long savedWifiLingerDuration = mWifiLingerDurationMs;
                                mWifiLingerDurationMs = -1;
                                updateWifiState("WiFi unexpectedly ON");
                                mWifiLingerDurationMs = savedWifiLingerDuration;
                            }
                        } else {
                            // This is definitely the case where another app has directly toggled
                            // WiFi via WifiManager.setWifiEnabled(true).  We want that
                            // to effectively set the WiFi Setting to ON.
                            Log.d(TAG, "WiFi re-enabled from outside the Settings Menu."
                                    + " Updating settings...");
                            // update our cache of states and settings to reflect reality
                            // before calling updateWifiState()
                            mWifiSetting = WIFI_SETTING_ON;
                            mSettings.putWifiSetting(mWifiSetting);

                            updateWifiState("WiFi turned on by setWifiEnabled(true)");
                        }
                    } else if (wifiState == WifiManager.WIFI_STATE_DISABLED) {
                        mRadioToggler.refreshRadioState();
                        if (WIFI_SETTING_ON.equals(mWifiSetting) && mWifiDesired) {
                            // Some app must have called setWifiEnabled(false) while WifiMediator
                            // is actively trying to keep WiFi on.
                            Log.d(TAG, "Wifi is OFF but mWifiDesired is TRUE. Updating state...");
                            updateWifiState("WiFi turned off by setWifiEnabled(false)");
                        }
                        // ignore all other potential cases where setWifiEnabled(false) can
                        // be called.  they're effectively a no-op, either because the WiFi Setting
                        // is already Off, or because wifi is not desired by WifiMediator

                        // we purposefully do not allow setWifiEnabled(false) to toggle the
                        // actual WiFi Setting to Off.
                    }
                    break;
                default:
                    // pass
            }
        }
    };

    public void dump(IndentingPrintWriter ipw) {
        ipw.println("======== WearWifiMediator ========");

        if (mDisableWifiMediator) {
            ipw.println("WifiMediator is disabled by developer setting. To re-enable, run:");
            ipw.println("  adb shell settings put global cw_disable_wifimediator 0");
        }

        ipw.printPair("mWifiSetting", mWifiSetting);
        ipw.printPair("mWifiEnabled", mRadioToggler.getRadioEnabled());
        ipw.println();
        ipw.printPair("mWifiConnected", mWifiConnected);
        ipw.printPair("mWifiLingering", mWifiLingering);
        ipw.println();
        ipw.printPair("mEnableWifiWhenCharging", mEnableWifiWhenCharging);
        ipw.printPair("mInHardwareLowPowerMode", mInHardwareLowPowerMode);
        ipw.println();
        ipw.printPair("mWifiDesired", mWifiDesired);
        ipw.printPair("mInWifiSettings", mInWifiSettings);
        ipw.printPair("mNumConfiguredNetworks", mNumConfiguredNetworks);
        ipw.println();
        ipw.printPair("mWifiOnWhenProxyDisconnected", mWifiOnWhenProxyDisconnected);
        ipw.println();
        ipw.printPair("mActivityMode", mActivityMode);
        ipw.println();

        ipw.println();
        mWifiBackoff.dump(ipw);
        ipw.println();

        ipw.println();
        mDecisionHistory.dump(ipw);
        ipw.println();

        ipw.println();
        ipw.println();
    }
}
