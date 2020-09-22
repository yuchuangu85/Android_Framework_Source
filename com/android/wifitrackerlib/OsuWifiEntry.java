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

package com.android.wifitrackerlib;

import static androidx.core.util.Preconditions.checkNotNull;

import static com.android.wifitrackerlib.Utils.getBestScanResultByLevel;
import static com.android.wifitrackerlib.WifiEntry.ConnectCallback.CONNECT_STATUS_FAILURE_UNKNOWN;

import android.annotation.MainThread;
import android.content.Context;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.ProvisioningCallback;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Pair;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * WifiEntry representation of an Online Sign-up entry, uniquely identified by FQDN.
 */
class OsuWifiEntry extends WifiEntry {
    static final String KEY_PREFIX = "OsuWifiEntry:";

    private final Object mLock = new Object();
    // Scan result list must be thread safe for generating the verbose scan summary
    @GuardedBy("mLock")
    @NonNull private final List<ScanResult> mCurrentScanResults = new ArrayList<>();

    @NonNull private final String mKey;
    @NonNull private final Context mContext;
    @NonNull private OsuProvider mOsuProvider;
    private String mOsuStatusString;
    private boolean mIsAlreadyProvisioned = false;

    /**
     * Create n OsuWifiEntry with the associated OsuProvider
     */
    OsuWifiEntry(@NonNull Context context, @NonNull Handler callbackHandler,
            @NonNull OsuProvider osuProvider,
            @NonNull WifiManager wifiManager,
            @NonNull WifiNetworkScoreCache scoreCache,
            boolean forSavedNetworksPage) throws IllegalArgumentException {
        super(callbackHandler, wifiManager, scoreCache, forSavedNetworksPage);

        checkNotNull(osuProvider, "Cannot construct with null osuProvider!");

        mContext = context;
        mOsuProvider = osuProvider;
        mKey = osuProviderToOsuWifiEntryKey(osuProvider);
    }

    @Override
    public String getKey() {
        return mKey;
    }

    @Override
    public String getTitle() {
        return mOsuProvider.getFriendlyName();
    }

    @Override
    public String getSummary(boolean concise) {
        // TODO(b/70983952): Add verbose summary
        if (mOsuStatusString != null) {
            return mOsuStatusString;
        } else if (isAlreadyProvisioned()) {
            return concise ? mContext.getString(R.string.wifi_passpoint_expired)
                    : mContext.getString(R.string.tap_to_renew_subscription_and_connect);
        } else {
            return mContext.getString(R.string.tap_to_sign_up);
        }
    }

    @Override
    public String getSsid() {
        // TODO(b/70983952): Fill this method in in case we need the SSID for verbose logging
        return "";
    }

    @Override
    @Security
    public int getSecurity() {
        return SECURITY_NONE;
    }

    @Override
    public String getMacAddress() {
        // TODO(b/70983952): Fill this method in in case we need the mac address for verbose logging
        return null;
    }

    @Override
    public boolean isMetered() {
        return false;
    }

    @Override
    public boolean isSaved() {
        return false;
    }

    @Override
    public boolean isSuggestion() {
        return false;
    }

    @Override
    public boolean isSubscription() {
        return false;
    }

    @Override
    public WifiConfiguration getWifiConfiguration() {
        return null;
    }

    @Override
    public boolean canConnect() {
        return mLevel != WIFI_LEVEL_UNREACHABLE
                && getConnectedState() == CONNECTED_STATE_DISCONNECTED;
    }

    @Override
    public void connect(@Nullable ConnectCallback callback) {
        mConnectCallback = callback;
        mWifiManager.startSubscriptionProvisioning(mOsuProvider, mContext.getMainExecutor(),
                new OsuWifiEntryProvisioningCallback());
    }

    // Exiting from the OSU flow should disconnect from the network.
    @Override
    public boolean canDisconnect() {
        return false;
    }

    @Override
    public void disconnect(@Nullable DisconnectCallback callback) {
    }

    @Override
    public boolean canForget() {
        return false;
    }

    @Override
    public void forget(@Nullable ForgetCallback callback) {
    }

    @Override
    public boolean canSignIn() {
        return false;
    }

    @Override
    public void signIn(@Nullable SignInCallback callback) {
        return;
    }

    @Override
    public boolean canShare() {
        return false;
    }

    @Override
    public boolean canEasyConnect() {
        return false;
    }

    @Override
    @MeteredChoice
    public int getMeteredChoice() {
        // Metered choice is meaningless for OSU entries
        return METERED_CHOICE_AUTO;
    }

    @Override
    public boolean canSetMeteredChoice() {
        return false;
    }

    @Override
    public void setMeteredChoice(int meteredChoice) {
        // Metered choice is meaningless for OSU entries
    }

    @Override
    @Privacy
    public int getPrivacy() {
        // MAC Randomization choice is meaningless for OSU entries.
        return PRIVACY_UNKNOWN;
    }

    @Override
    public boolean canSetPrivacy() {
        return false;
    }

    @Override
    public void setPrivacy(int privacy) {
        // MAC Randomization choice is meaningless for OSU entries.
    }

    @Override
    public boolean isAutoJoinEnabled() {
        return false;
    }

    @Override
    public boolean canSetAutoJoinEnabled() {
        return false;
    }

    @Override
    public void setAutoJoinEnabled(boolean enabled) {
    }

    @Override
    public String getSecurityString(boolean concise) {
        return "";
    }

    @Override
    public boolean isExpired() {
        return false;
    }

    @WorkerThread
    void updateScanResultInfo(@Nullable List<ScanResult> scanResults)
            throws IllegalArgumentException {
        if (scanResults == null) scanResults = new ArrayList<>();

        synchronized (mLock) {
            mCurrentScanResults.clear();
            mCurrentScanResults.addAll(scanResults);
        }

        final ScanResult bestScanResult = getBestScanResultByLevel(scanResults);
        if (getConnectedState() == CONNECTED_STATE_DISCONNECTED) {
            mLevel = bestScanResult != null
                    ? mWifiManager.calculateSignalLevel(bestScanResult.level)
                    : WIFI_LEVEL_UNREACHABLE;
        }
        notifyOnUpdated();
    }

    @NonNull
    static String osuProviderToOsuWifiEntryKey(@NonNull OsuProvider osuProvider) {
        checkNotNull(osuProvider, "Cannot create key with null OsuProvider!");
        return KEY_PREFIX + osuProvider.getFriendlyName() + ","
                + osuProvider.getServerUri().toString();
    }

    @WorkerThread
    @Override
    protected boolean connectionInfoMatches(@NonNull WifiInfo wifiInfo,
            @NonNull NetworkInfo networkInfo) {
        return wifiInfo.isOsuAp() && TextUtils.equals(
                wifiInfo.getPasspointProviderFriendlyName(), mOsuProvider.getFriendlyName());
    }

    @Override
    String getScanResultDescription() {
        // TODO(b/70983952): Fill this method in.
        return "";
    }

    OsuProvider getOsuProvider() {
        return mOsuProvider;
    }

    boolean isAlreadyProvisioned() {
        return mIsAlreadyProvisioned;
    }

    void setAlreadyProvisioned(boolean isAlreadyProvisioned) {
        mIsAlreadyProvisioned = isAlreadyProvisioned;
    }

    class OsuWifiEntryProvisioningCallback extends ProvisioningCallback {
        @Override
        @MainThread public void onProvisioningFailure(int status) {
            if (TextUtils.equals(
                    mOsuStatusString, mContext.getString(R.string.osu_completing_sign_up))) {
                mOsuStatusString = mContext.getString(R.string.osu_sign_up_failed);
            } else {
                mOsuStatusString = mContext.getString(R.string.osu_connect_failed);
            }
            if (mConnectCallback != null) {
                mConnectCallback.onConnectResult(CONNECT_STATUS_FAILURE_UNKNOWN);
            }
            notifyOnUpdated();
        }

        @Override
        @MainThread public void onProvisioningStatus(int status) {
            String newStatusString = null;
            switch (status) {
                case OSU_STATUS_AP_CONNECTING:
                case OSU_STATUS_AP_CONNECTED:
                case OSU_STATUS_SERVER_CONNECTING:
                case OSU_STATUS_SERVER_VALIDATED:
                case OSU_STATUS_SERVER_CONNECTED:
                case OSU_STATUS_INIT_SOAP_EXCHANGE:
                case OSU_STATUS_WAITING_FOR_REDIRECT_RESPONSE:
                    newStatusString = String.format(mContext.getString(
                            R.string.osu_opening_provider),
                            mOsuProvider.getFriendlyName());
                    break;
                case OSU_STATUS_REDIRECT_RESPONSE_RECEIVED:
                case OSU_STATUS_SECOND_SOAP_EXCHANGE:
                case OSU_STATUS_THIRD_SOAP_EXCHANGE:
                case OSU_STATUS_RETRIEVING_TRUST_ROOT_CERTS:
                    newStatusString = mContext.getString(R.string.osu_completing_sign_up);
                    break;
            }
            boolean updated = !TextUtils.equals(mOsuStatusString, newStatusString);
            mOsuStatusString = newStatusString;
            if (updated) {
                notifyOnUpdated();
            }
        }

        @Override
        @MainThread public void onProvisioningComplete() {
            mOsuStatusString = mContext.getString(R.string.osu_sign_up_complete);
            notifyOnUpdated();

            PasspointConfiguration passpointConfig = mWifiManager
                    .getMatchingPasspointConfigsForOsuProviders(Collections.singleton(mOsuProvider))
                    .get(mOsuProvider);
            if (passpointConfig == null) {
                // Failed to find the config we just provisioned
                if (mConnectCallback != null) {
                    mConnectCallback.onConnectResult(CONNECT_STATUS_FAILURE_UNKNOWN);
                }
                return;
            }
            String uniqueId = passpointConfig.getUniqueId();
            for (Pair<WifiConfiguration, Map<Integer, List<ScanResult>>> pairing :
                    mWifiManager.getAllMatchingWifiConfigs(mWifiManager.getScanResults())) {
                WifiConfiguration config = pairing.first;
                if (TextUtils.equals(config.getKey(), uniqueId)) {
                    List<ScanResult> homeScans =
                            pairing.second.get(WifiManager.PASSPOINT_HOME_NETWORK);
                    List<ScanResult> roamingScans =
                            pairing.second.get(WifiManager.PASSPOINT_ROAMING_NETWORK);
                    ScanResult bestScan;
                    if (homeScans != null && !homeScans.isEmpty()) {
                        bestScan = getBestScanResultByLevel(homeScans);
                    } else if (roamingScans != null && !roamingScans.isEmpty()) {
                        bestScan = getBestScanResultByLevel(roamingScans);
                    } else {
                        break;
                    }
                    config.SSID = "\"" + bestScan.SSID + "\"";
                    mWifiManager.connect(config, null /* ActionListener */);
                    return;
                }
            }

            // Failed to find the network we provisioned for
            if (mConnectCallback != null) {
                mConnectCallback.onConnectResult(CONNECT_STATUS_FAILURE_UNKNOWN);
            }
        }
    }
}
