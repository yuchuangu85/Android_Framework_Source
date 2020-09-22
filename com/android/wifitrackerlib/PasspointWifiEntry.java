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

import static android.net.wifi.WifiInfo.sanitizeSsid;

import static androidx.core.util.Preconditions.checkNotNull;

import static com.android.wifitrackerlib.Utils.getAppLabel;
import static com.android.wifitrackerlib.Utils.getAutoConnectDescription;
import static com.android.wifitrackerlib.Utils.getAverageSpeedFromScanResults;
import static com.android.wifitrackerlib.Utils.getBestScanResultByLevel;
import static com.android.wifitrackerlib.Utils.getCarrierNameForSubId;
import static com.android.wifitrackerlib.Utils.getCurrentNetworkCapabilitiesInformation;
import static com.android.wifitrackerlib.Utils.getDisconnectedStateDescription;
import static com.android.wifitrackerlib.Utils.getImsiProtectionDescription;
import static com.android.wifitrackerlib.Utils.getMeteredDescription;
import static com.android.wifitrackerlib.Utils.getNetworkDetailedState;
import static com.android.wifitrackerlib.Utils.getSecurityTypeFromWifiConfiguration;
import static com.android.wifitrackerlib.Utils.getSpeedDescription;
import static com.android.wifitrackerlib.Utils.getSpeedFromWifiInfo;
import static com.android.wifitrackerlib.Utils.getSubIdForConfig;
import static com.android.wifitrackerlib.Utils.getVerboseLoggingDescription;

import android.content.Context;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.os.Handler;
import android.text.TextUtils;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * WifiEntry representation of a subscribed Passpoint network, uniquely identified by FQDN.
 */
@VisibleForTesting
public class PasspointWifiEntry extends WifiEntry implements WifiEntry.WifiEntryCallback {
    static final String KEY_PREFIX = "PasspointWifiEntry:";

    private final Object mLock = new Object();
    // Scan result list must be thread safe for generating the verbose scan summary
    @GuardedBy("mLock")
    private final List<ScanResult> mCurrentHomeScanResults = new ArrayList<>();
    @GuardedBy("mLock")
    private final List<ScanResult> mCurrentRoamingScanResults = new ArrayList<>();

    @NonNull private final String mKey;
    @NonNull private String mFqdn;
    @NonNull private String mFriendlyName;
    @NonNull private final Context mContext;
    @Nullable
    private PasspointConfiguration mPasspointConfig;
    @Nullable private WifiConfiguration mWifiConfig;
    private @Security int mSecurity = SECURITY_EAP;
    private boolean mIsRoaming = false;
    private OsuWifiEntry mOsuWifiEntry;

    protected long mSubscriptionExpirationTimeInMillis;

    // PasspointConfiguration#setMeteredOverride(int meteredOverride) is a hide API and we can't
    // set it in PasspointWifiEntry#setMeteredChoice(int meteredChoice).
    // For PasspointWifiEntry#getMeteredChoice() to return correct value right after
    // PasspointWifiEntry#setMeteredChoice(int meteredChoice), cache
    // PasspointConfiguration#getMeteredOverride() in this variable.
    private int mMeteredOverride = METERED_CHOICE_AUTO;

    /**
     * Create a PasspointWifiEntry with the associated PasspointConfiguration
     */
    PasspointWifiEntry(@NonNull Context context, @NonNull Handler callbackHandler,
            @NonNull PasspointConfiguration passpointConfig,
            @NonNull WifiManager wifiManager,
            @NonNull WifiNetworkScoreCache scoreCache,
            boolean forSavedNetworksPage) throws IllegalArgumentException {
        super(callbackHandler, wifiManager, scoreCache, forSavedNetworksPage);

        checkNotNull(passpointConfig, "Cannot construct with null PasspointConfiguration!");

        mContext = context;
        mPasspointConfig = passpointConfig;
        mKey = uniqueIdToPasspointWifiEntryKey(passpointConfig.getUniqueId());
        mFqdn = passpointConfig.getHomeSp().getFqdn();
        mFriendlyName = passpointConfig.getHomeSp().getFriendlyName();
        mSubscriptionExpirationTimeInMillis =
                passpointConfig.getSubscriptionExpirationTimeMillis();
        mMeteredOverride = mPasspointConfig.getMeteredOverride();
    }

    /**
     * Create a PasspointWifiEntry with the associated WifiConfiguration for use with network
     * suggestions, since WifiManager#getAllMatchingWifiConfigs() does not provide a corresponding
     * PasspointConfiguration.
     */
    PasspointWifiEntry(@NonNull Context context, @NonNull Handler callbackHandler,
            @NonNull WifiConfiguration wifiConfig,
            @NonNull WifiManager wifiManager,
            @NonNull WifiNetworkScoreCache scoreCache,
            boolean forSavedNetworksPage) throws IllegalArgumentException {
        super(callbackHandler, wifiManager, scoreCache, forSavedNetworksPage);

        checkNotNull(wifiConfig, "Cannot construct with null PasspointConfiguration!");
        if (!wifiConfig.isPasspoint()) {
            throw new IllegalArgumentException("Given WifiConfiguration is not for Passpoint!");
        }

        mContext = context;
        mWifiConfig = wifiConfig;
        mKey = uniqueIdToPasspointWifiEntryKey(wifiConfig.getKey());
        mFqdn = wifiConfig.FQDN;
        mFriendlyName = mWifiConfig.providerFriendlyName;
    }

    @Override
    public String getKey() {
        return mKey;
    }

    @Override
    @ConnectedState
    public int getConnectedState() {
        if (isExpired()) {
            if (super.getConnectedState() == CONNECTED_STATE_DISCONNECTED
                    && mOsuWifiEntry != null) {
                return mOsuWifiEntry.getConnectedState();
            }
        }
        return super.getConnectedState();
    }

    @Override
    public String getTitle() {
        return mFriendlyName;
    }

    @Override
    public String getSummary(boolean concise) {
        StringJoiner sj = new StringJoiner(mContext.getString(R.string.summary_separator));

        if (isExpired()) {
            if (mOsuWifiEntry != null) {
                sj.add(mOsuWifiEntry.getSummary(concise));
            } else {
                sj.add(mContext.getString(R.string.wifi_passpoint_expired));
            }
        } else if (getConnectedState() == CONNECTED_STATE_DISCONNECTED) {
            String disconnectDescription = getDisconnectedStateDescription(mContext, this);
            if (TextUtils.isEmpty(disconnectDescription)) {
                if (concise) {
                    sj.add(mContext.getString(R.string.wifi_disconnected));
                } else if (!mForSavedNetworksPage) {
                    if (mWifiConfig != null && mWifiConfig.fromWifiNetworkSuggestion) {
                        String carrierName = getCarrierNameForSubId(mContext,
                                getSubIdForConfig(mContext, mWifiConfig));
                        String suggestorLabel = getAppLabel(mContext, mWifiConfig.creatorName);
                        if (TextUtils.isEmpty(suggestorLabel)) {
                            // Fall-back to the package name in case the app label is missing
                            suggestorLabel = mWifiConfig.creatorName;
                        }
                        sj.add(mContext.getString(R.string.available_via_app, carrierName != null
                                ? carrierName
                                : suggestorLabel));
                    } else {
                        sj.add(mContext.getString(R.string.wifi_remembered));
                    }
                }
            } else {
                sj.add(disconnectDescription);
            }
        } else {
            String connectDescription = getConnectStateDescription();
            if (!TextUtils.isEmpty(connectDescription)) {
                sj.add(connectDescription);
            }
        }

        String speedDescription = getSpeedDescription(mContext, this);
        if (!TextUtils.isEmpty(speedDescription)) {
            sj.add(speedDescription);
        }

        String autoConnectDescription = getAutoConnectDescription(mContext, this);
        if (!TextUtils.isEmpty(autoConnectDescription)) {
            sj.add(autoConnectDescription);
        }

        String meteredDescription = getMeteredDescription(mContext, this);
        if (!TextUtils.isEmpty(meteredDescription)) {
            sj.add(meteredDescription);
        }

        if (!concise) {
            String verboseLoggingDescription = getVerboseLoggingDescription(this);
            if (!TextUtils.isEmpty(verboseLoggingDescription)) {
                sj.add(verboseLoggingDescription);
            }
        }

        return sj.toString();
    }

    private String getConnectStateDescription() {
        if (getConnectedState() == CONNECTED_STATE_CONNECTED) {
            // For network suggestions
            final String suggestionOrSpecifierPackageName = mWifiInfo != null
                    ? mWifiInfo.getRequestingPackageName() : null;
            if (!TextUtils.isEmpty(suggestionOrSpecifierPackageName)) {
                String carrierName = mWifiConfig != null
                        ? getCarrierNameForSubId(mContext, getSubIdForConfig(mContext, mWifiConfig))
                        : null;
                String suggestorLabel = getAppLabel(mContext, suggestionOrSpecifierPackageName);
                if (TextUtils.isEmpty(suggestorLabel)) {
                    // Fall-back to the package name in case the app label is missing
                    suggestorLabel = suggestionOrSpecifierPackageName;
                }
                return mContext.getString(R.string.connected_via_app, carrierName != null
                        ? carrierName
                        : suggestorLabel);
            }
            String networkCapabilitiesinformation =
                    getCurrentNetworkCapabilitiesInformation(mContext, mNetworkCapabilities);
            if (!TextUtils.isEmpty(networkCapabilitiesinformation)) {
                return networkCapabilitiesinformation;
            }
        }

        return getNetworkDetailedState(mContext, mNetworkInfo);
    }

    @Override
    public CharSequence getSecondSummary() {
        return getConnectedState() == CONNECTED_STATE_CONNECTED
                ? getImsiProtectionDescription(mContext, mWifiConfig) : "";
    }

    @Override
    public String getSsid() {
        if (mWifiInfo != null) {
            return sanitizeSsid(mWifiInfo.getSSID());
        }

        return mWifiConfig != null ? sanitizeSsid(mWifiConfig.SSID) : null;
    }

    @Override
    @Security
    public int getSecurity() {
        return mSecurity;
    }

    @Override
    public String getMacAddress() {
        if (mWifiConfig == null || getPrivacy() != PRIVACY_RANDOMIZED_MAC) {
            final String[] factoryMacs = mWifiManager.getFactoryMacAddresses();
            if (factoryMacs.length > 0) {
                return factoryMacs[0];
            } else {
                return null;
            }
        } else {
            return mWifiConfig.getRandomizedMacAddress().toString();
        }
    }

    @Override
    public boolean isMetered() {
        return getMeteredChoice() == METERED_CHOICE_METERED
                || (mWifiConfig != null && mWifiConfig.meteredHint);
    }

    @Override
    public boolean isSaved() {
        return false;
    }

    @Override
    public boolean isSuggestion() {
        return mWifiConfig != null && mWifiConfig.fromWifiNetworkSuggestion;
    }

    @Override
    public boolean isSubscription() {
        return mPasspointConfig != null;
    }

    @Override
    public WifiConfiguration getWifiConfiguration() {
        return null;
    }

    @Override
    public boolean canConnect() {
        if (isExpired()) {
            return mOsuWifiEntry != null && mOsuWifiEntry.canConnect();
        }

        return mLevel != WIFI_LEVEL_UNREACHABLE
                && getConnectedState() == CONNECTED_STATE_DISCONNECTED && mWifiConfig != null;
    }

    @Override
    public void connect(@Nullable ConnectCallback callback) {
        if (isExpired()) {
            if (mOsuWifiEntry != null) {
                mOsuWifiEntry.connect(callback);
                return;
            }
        }

        mConnectCallback = callback;

        if (mWifiConfig == null) {
            // We should not be able to call connect() if mWifiConfig is null
            new ConnectActionListener().onFailure(0);
        }
        mWifiManager.connect(mWifiConfig, new ConnectActionListener());
    }

    @Override
    public boolean canDisconnect() {
        return getConnectedState() == CONNECTED_STATE_CONNECTED;
    }

    @Override
    public void disconnect(@Nullable DisconnectCallback callback) {
        if (canDisconnect()) {
            mCalledDisconnect = true;
            mDisconnectCallback = callback;
            mCallbackHandler.postDelayed(() -> {
                if (callback != null && mCalledDisconnect) {
                    callback.onDisconnectResult(
                            DisconnectCallback.DISCONNECT_STATUS_FAILURE_UNKNOWN);
                }
            }, 10_000 /* delayMillis */);
            mWifiManager.disableEphemeralNetwork(mWifiConfig.FQDN);
            mWifiManager.disconnect();
        }
    }

    @Override
    public boolean canForget() {
        return !isSuggestion() && mPasspointConfig != null;
    }

    @Override
    public void forget(@Nullable ForgetCallback callback) {
        if (!canForget()) {
            return;
        }

        mForgetCallback = callback;
        mWifiManager.removePasspointConfiguration(mPasspointConfig.getHomeSp().getFqdn());
        new ForgetActionListener().onSuccess();
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
        if (mMeteredOverride == WifiConfiguration.METERED_OVERRIDE_METERED) {
            return METERED_CHOICE_METERED;
        } else if (mMeteredOverride == WifiConfiguration.METERED_OVERRIDE_NOT_METERED) {
            return METERED_CHOICE_UNMETERED;
        }
        return METERED_CHOICE_AUTO;
    }

    @Override
    public boolean canSetMeteredChoice() {
        return !isSuggestion() && mPasspointConfig != null;
    }

    @Override
    public void setMeteredChoice(int meteredChoice) {
        if (!canSetMeteredChoice()) {
            return;
        }

        switch (meteredChoice) {
            case METERED_CHOICE_AUTO:
                mMeteredOverride = WifiConfiguration.METERED_OVERRIDE_NONE;
                break;
            case METERED_CHOICE_METERED:
                mMeteredOverride = WifiConfiguration.METERED_OVERRIDE_METERED;
                break;
            case METERED_CHOICE_UNMETERED:
                mMeteredOverride = WifiConfiguration.METERED_OVERRIDE_NOT_METERED;
                break;
            default:
                // Do nothing.
                return;
        }
        mWifiManager.setPasspointMeteredOverride(mPasspointConfig.getHomeSp().getFqdn(),
                mMeteredOverride);
    }

    @Override
    public boolean canSetPrivacy() {
        return !isSuggestion() && mPasspointConfig != null;
    }

    @Override
    @Privacy
    public int getPrivacy() {
        if (mPasspointConfig == null) {
            return PRIVACY_RANDOMIZED_MAC;
        }

        return mPasspointConfig.isMacRandomizationEnabled()
                ? PRIVACY_RANDOMIZED_MAC : PRIVACY_DEVICE_MAC;
    }

    @Override
    public void setPrivacy(int privacy) {
        if (!canSetPrivacy()) {
            return;
        }

        mWifiManager.setMacRandomizationSettingPasspointEnabled(
                mPasspointConfig.getHomeSp().getFqdn(),
                privacy == PRIVACY_DEVICE_MAC ? false : true);
    }

    @Override
    public boolean isAutoJoinEnabled() {
        // Suggestion network; use WifiConfig instead
        if (mPasspointConfig == null && mWifiConfig != null) {
            return mWifiConfig.allowAutojoin;
        }

        return mPasspointConfig.isAutojoinEnabled();
    }

    @Override
    public boolean canSetAutoJoinEnabled() {
        return true;
    }

    @Override
    public void setAutoJoinEnabled(boolean enabled) {
        if (mPasspointConfig == null && mWifiConfig != null) {
            mWifiManager.allowAutojoin(mWifiConfig.networkId, enabled);
            return;
        }

        mWifiManager.allowAutojoinPasspoint(mPasspointConfig.getHomeSp().getFqdn(), enabled);
    }

    @Override
    public String getSecurityString(boolean concise) {
        return concise ? mContext.getString(R.string.wifi_security_short_eap) :
                mContext.getString(R.string.wifi_security_eap);
    }

    @Override
    public boolean isExpired() {
        if (mSubscriptionExpirationTimeInMillis <= 0) {
            // Expiration time not specified.
            return false;
        } else {
            return System.currentTimeMillis() >= mSubscriptionExpirationTimeInMillis;
        }
    }

    @WorkerThread
    void updatePasspointConfig(@Nullable PasspointConfiguration passpointConfig) {
        mPasspointConfig = passpointConfig;
        if (mPasspointConfig != null) {
            mFriendlyName = passpointConfig.getHomeSp().getFriendlyName();
            mSubscriptionExpirationTimeInMillis =
                    passpointConfig.getSubscriptionExpirationTimeMillis();
            mMeteredOverride = passpointConfig.getMeteredOverride();
        }
        notifyOnUpdated();
    }

    @WorkerThread
    void updateScanResultInfo(@Nullable WifiConfiguration wifiConfig,
            @Nullable List<ScanResult> homeScanResults,
            @Nullable List<ScanResult> roamingScanResults)
            throws IllegalArgumentException {
        mIsRoaming = false;
        mWifiConfig = wifiConfig;
        synchronized (mLock) {
            mCurrentHomeScanResults.clear();
            mCurrentRoamingScanResults.clear();
            if (homeScanResults != null) {
                mCurrentHomeScanResults.addAll(homeScanResults);
            }
            if (roamingScanResults != null) {
                mCurrentRoamingScanResults.addAll(roamingScanResults);
            }
        }
        if (mWifiConfig != null) {
            mSecurity = getSecurityTypeFromWifiConfiguration(wifiConfig);
            List<ScanResult> currentScanResults = new ArrayList<>();
            ScanResult bestScanResult = null;
            if (homeScanResults != null && !homeScanResults.isEmpty()) {
                currentScanResults.addAll(homeScanResults);
            } else if (roamingScanResults != null && !roamingScanResults.isEmpty()) {
                currentScanResults.addAll(roamingScanResults);
                mIsRoaming = true;
            }
            bestScanResult = getBestScanResultByLevel(currentScanResults);
            if (bestScanResult != null) {
                mWifiConfig.SSID = "\"" + bestScanResult.SSID + "\"";
            }
            if (getConnectedState() == CONNECTED_STATE_DISCONNECTED) {
                mLevel = bestScanResult != null
                        ? mWifiManager.calculateSignalLevel(bestScanResult.level)
                        : WIFI_LEVEL_UNREACHABLE;
                // Average speed is used to prevent speed label flickering from multiple APs.
                mSpeed = getAverageSpeedFromScanResults(mScoreCache, currentScanResults);
            }
        } else {
            mLevel = WIFI_LEVEL_UNREACHABLE;
        }
        notifyOnUpdated();
    }

    @WorkerThread
    void onScoreCacheUpdated() {
        if (mWifiInfo != null) {
            mSpeed = getSpeedFromWifiInfo(mScoreCache, mWifiInfo);
        } else {
            synchronized (mLock) {
                // Average speed is used to prevent speed label flickering from multiple APs.
                if (!mCurrentHomeScanResults.isEmpty()) {
                    mSpeed = getAverageSpeedFromScanResults(mScoreCache, mCurrentHomeScanResults);
                } else {
                    mSpeed = getAverageSpeedFromScanResults(mScoreCache,
                            mCurrentRoamingScanResults);
                }
            }
        }
        notifyOnUpdated();
    }

    @WorkerThread
    @Override
    protected boolean connectionInfoMatches(@NonNull WifiInfo wifiInfo,
            @NonNull NetworkInfo networkInfo) {
        if (!wifiInfo.isPasspointAp()) {
            return false;
        }

        // Match with FQDN until WifiInfo supports returning the passpoint uniqueID
        return TextUtils.equals(wifiInfo.getPasspointFqdn(), mFqdn);
    }

    @NonNull
    static String uniqueIdToPasspointWifiEntryKey(@NonNull String uniqueId) {
        checkNotNull(uniqueId, "Cannot create key with null unique id!");
        return KEY_PREFIX + uniqueId;
    }

    @Override
    String getScanResultDescription() {
        // TODO(b/70983952): Fill this method in.
        return "";
    }

    @Override
    String getNetworkSelectionDescription() {
        return Utils.getNetworkSelectionDescription(mWifiConfig);
    }

    /** Pass a reference to a matching OsuWifiEntry for expiration handling */
    void setOsuWifiEntry(OsuWifiEntry osuWifiEntry) {
        mOsuWifiEntry = osuWifiEntry;
        mOsuWifiEntry.setListener(this);
    }

    /** Callback for updates to the linked OsuWifiEntry */
    @Override
    public void onUpdated() {
        notifyOnUpdated();
    }
}
