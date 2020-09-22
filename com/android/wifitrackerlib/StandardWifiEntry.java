/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.DISABLED_AUTHENTICATION_FAILURE;
import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.DISABLED_AUTHENTICATION_NO_CREDENTIALS;
import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.DISABLED_BY_WRONG_PASSWORD;
import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.NETWORK_SELECTION_ENABLED;
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
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkScoreManager;
import android.net.NetworkScorerAppData;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.GuardedBy;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * WifiEntry representation of a logical Wi-Fi network, uniquely identified by SSID and security.
 *
 * This type of WifiEntry can represent both open and saved networks.
 */
@VisibleForTesting
public class StandardWifiEntry extends WifiEntry {
    static final String KEY_PREFIX = "StandardWifiEntry:";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            EAP_WPA,
            EAP_WPA2_WPA3,
            EAP_UNKNOWN
    })

    public @interface EapType {}

    private static final int EAP_WPA = 0;       // WPA-EAP
    private static final int EAP_WPA2_WPA3 = 1; // RSN-EAP
    private static final int EAP_UNKNOWN = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            PSK_WPA,
            PSK_WPA2,
            PSK_WPA_WPA2,
            PSK_UNKNOWN
    })

    public @interface PskType {}

    private static final int PSK_WPA = 0;
    private static final int PSK_WPA2 = 1;
    private static final int PSK_WPA_WPA2 = 2;
    private static final int PSK_UNKNOWN = 3;

    private final Object mLock = new Object();
    // Scan result list must be thread safe for generating the verbose scan summary
    @GuardedBy("mLock")
    @NonNull private final List<ScanResult> mCurrentScanResults = new ArrayList<>();

    @NonNull private final String mKey;
    @NonNull private final String mSsid;
    @NonNull private final Context mContext;
    private final @Security int mSecurity;
    private @EapType int mEapType = EAP_UNKNOWN;
    private @PskType int mPskType = PSK_UNKNOWN;
    @Nullable private WifiConfiguration mWifiConfig;
    private boolean mIsUserShareable = false;
    @Nullable private String mRecommendationServiceLabel;

    private boolean mShouldAutoOpenCaptivePortal = false;

    StandardWifiEntry(@NonNull Context context, @NonNull Handler callbackHandler,
            @NonNull String key,
            @NonNull List<ScanResult> scanResults,
            @NonNull WifiManager wifiManager,
            @NonNull WifiNetworkScoreCache scoreCache,
            boolean forSavedNetworksPage) throws IllegalArgumentException {
        this(context, callbackHandler, key, wifiManager, scoreCache,
                forSavedNetworksPage);

        checkNotNull(scanResults, "Cannot construct with null ScanResult list!");
        if (scanResults.isEmpty()) {
            throw new IllegalArgumentException("Cannot construct with empty ScanResult list!");
        }
        updateScanResultInfo(scanResults);
        updateRecommendationServiceLabel();
    }

    StandardWifiEntry(@NonNull Context context, @NonNull Handler callbackHandler,
            @NonNull String key, @NonNull WifiConfiguration config,
            @NonNull WifiManager wifiManager,
            @NonNull WifiNetworkScoreCache scoreCache,
            boolean forSavedNetworksPage) throws IllegalArgumentException {
        this(context, callbackHandler, key, wifiManager, scoreCache,
                forSavedNetworksPage);

        checkNotNull(config, "Cannot construct with null config!");
        checkNotNull(config.SSID, "Supplied config must have an SSID!");
        mWifiConfig = config;
        updateRecommendationServiceLabel();
    }

    StandardWifiEntry(@NonNull Context context, @NonNull Handler callbackHandler,
            @NonNull String key, @NonNull WifiManager wifiManager,
            @NonNull WifiNetworkScoreCache scoreCache,
            boolean forSavedNetworksPage) {
        super(callbackHandler, wifiManager, scoreCache, forSavedNetworksPage);

        mContext = context;
        mKey = key;
        try {
            final int prefixDelimiter = key.indexOf(":");
            final int securityDelimiter = key.lastIndexOf(",");
            mSsid = key.substring(prefixDelimiter + 1, securityDelimiter);
            mSecurity = Integer.valueOf(key.substring(securityDelimiter + 1));
        } catch (StringIndexOutOfBoundsException | NumberFormatException e) {
            throw new IllegalArgumentException("Malformed key: " + key);
        }
        updateRecommendationServiceLabel();
    }

    @Override
    public String getKey() {
        return mKey;
    }

    @Override
    public String getTitle() {
        return mSsid;
    }

    @Override
    public String getSummary(boolean concise) {
        StringJoiner sj = new StringJoiner(mContext.getString(R.string.summary_separator));

        if (!concise && mForSavedNetworksPage && isSaved()) {
            final CharSequence appLabel = getAppLabel(mContext, mWifiConfig.creatorName);
            if (!TextUtils.isEmpty(appLabel)) {
                sj.add(mContext.getString(R.string.saved_network, appLabel));
            }
        }

        if (getConnectedState() == CONNECTED_STATE_DISCONNECTED) {
            String disconnectDescription = getDisconnectedStateDescription(mContext, this);
            if (TextUtils.isEmpty(disconnectDescription)) {
                if (concise) {
                    sj.add(mContext.getString(R.string.wifi_disconnected));
                } else if (!mForSavedNetworksPage) {
                    // Summary for unconnected suggested network
                    if (isSuggestion()) {
                        String carrierName = getCarrierNameForSubId(mContext,
                                getSubIdForConfig(mContext, mWifiConfig));
                        String suggestorName = getAppLabel(mContext, mWifiConfig.creatorName);
                        if (TextUtils.isEmpty(suggestorName)) {
                            // Fall-back to the package name in case the app label is missing
                            suggestorName = mWifiConfig.creatorName;
                        }
                        sj.add(mContext.getString(R.string.available_via_app, carrierName != null
                                ? carrierName : suggestorName));
                    } else if (isSaved()) {
                        sj.add(mContext.getString(R.string.wifi_remembered));
                    }
                }
            } else {
                sj.add(disconnectDescription);
            }
        } else {
            final String connectDescription = getConnectStateDescription();
            if (!TextUtils.isEmpty(connectDescription)) {
                sj.add(connectDescription);
            }
        }

        final String speedDescription = getSpeedDescription(mContext, this);
        if (!TextUtils.isEmpty(speedDescription)) {
            sj.add(speedDescription);
        }

        final String autoConnectDescription = getAutoConnectDescription(mContext, this);
        if (!TextUtils.isEmpty(autoConnectDescription)) {
            sj.add(autoConnectDescription);
        }

        final String meteredDescription = getMeteredDescription(mContext, this);
        if (!TextUtils.isEmpty(meteredDescription)) {
            sj.add(meteredDescription);
        }

        if (!concise) {
            final String verboseLoggingDescription = getVerboseLoggingDescription(this);
            if (!TextUtils.isEmpty(verboseLoggingDescription)) {
                sj.add(verboseLoggingDescription);
            }
        }

        return sj.toString();
    }

    private String getConnectStateDescription() {
        if (getConnectedState() == CONNECTED_STATE_CONNECTED) {
            // For suggestion or specifier networks
            final String suggestionOrSpecifierPackageName = mWifiInfo != null
                    ? mWifiInfo.getRequestingPackageName() : null;
            if (!TextUtils.isEmpty(suggestionOrSpecifierPackageName)) {
                String carrierName = mWifiConfig != null
                        ? getCarrierNameForSubId(mContext, getSubIdForConfig(mContext, mWifiConfig))
                        : null;
                String suggestorName = getAppLabel(mContext, suggestionOrSpecifierPackageName);
                if (TextUtils.isEmpty(suggestorName)) {
                    // Fall-back to the package name in case the app label is missing
                    suggestorName = suggestionOrSpecifierPackageName;
                }
                return mContext.getString(R.string.connected_via_app, carrierName != null
                        ? carrierName : suggestorName);
            }

            if (!isSaved() && !isSuggestion()) {
                // Special case for connected + ephemeral networks.
                if (!TextUtils.isEmpty(mRecommendationServiceLabel)) {
                    return String.format(mContext.getString(R.string.connected_via_network_scorer),
                            mRecommendationServiceLabel);
                }
                return mContext.getString(R.string.connected_via_network_scorer_default);
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
                ? getImsiProtectionDescription(mContext, getWifiConfiguration()) : "";
    }

    @Override
    public String getSsid() {
        return mSsid;
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
        return mWifiConfig != null && !mWifiConfig.isEphemeral();
    }

    @Override
    public boolean isSuggestion() {
        return mWifiConfig != null && mWifiConfig.fromWifiNetworkSuggestion;
    }

    @Override
    public boolean isSubscription() {
        return false;
    }

    @Override
    public WifiConfiguration getWifiConfiguration() {
        if (!isSaved()) {
            return null;
        }
        return mWifiConfig;
    }

    @Override
    public ConnectedInfo getConnectedInfo() {
        return mConnectedInfo;
    }

    @Override
    public boolean canConnect() {
        return mLevel != WIFI_LEVEL_UNREACHABLE
                && getConnectedState() == CONNECTED_STATE_DISCONNECTED;
    }

    @Override
    public void connect(@Nullable ConnectCallback callback) {
        mConnectCallback = callback;
        // We should flag this network to auto-open captive portal since this method represents
        // the user manually connecting to a network (i.e. not auto-join).
        mShouldAutoOpenCaptivePortal = true;

        if (isSaved() || isSuggestion()) {
            // Saved/suggested network
            mWifiManager.connect(mWifiConfig.networkId, new ConnectActionListener());
        } else {
            // Unsaved network
            if (mSecurity == SECURITY_NONE
                    || mSecurity == SECURITY_OWE) {
                // Open network
                final WifiConfiguration connectConfig = new WifiConfiguration();
                connectConfig.SSID = "\"" + mSsid + "\"";

                if (mSecurity == SECURITY_OWE) {
                    // Use OWE if possible
                    connectConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.OWE);
                    connectConfig.requirePmf = true;
                } else {
                    connectConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                }
                mWifiManager.connect(connectConfig, new ConnectActionListener());
            } else {
                // Secure network
                if (callback != null) {
                    mCallbackHandler.post(() ->
                            callback.onConnectResult(
                                    ConnectCallback.CONNECT_STATUS_FAILURE_NO_CONFIG));
                }
            }
        }
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
            mWifiManager.disableEphemeralNetwork(mWifiConfig.SSID);
            mWifiManager.disconnect();
        }
    }

    @Override
    public boolean canForget() {
        return getWifiConfiguration() != null;
    }

    @Override
    public void forget(@Nullable ForgetCallback callback) {
        if (canForget()) {
            mForgetCallback = callback;
            mWifiManager.forget(mWifiConfig.networkId, new ForgetActionListener());
        }
    }

    @Override
    public boolean canSignIn() {
        return mNetworkCapabilities != null
                && mNetworkCapabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
    }

    @Override
    public void signIn(@Nullable SignInCallback callback) {
        if (canSignIn()) {
            // canSignIn() implies that this WifiEntry is the currently connected network, so use
            // getCurrentNetwork() to start the captive portal app.
            ((ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE))
                    .startCaptivePortalApp(mWifiManager.getCurrentNetwork());
        }
    }

    /**
     * Returns whether the network can be shared via QR code.
     * See https://github.com/zxing/zxing/wiki/Barcode-Contents#wi-fi-network-config-android-ios-11
     */
    @Override
    public boolean canShare() {
        if (getWifiConfiguration() == null) {
            return false;
        }

        switch (mSecurity) {
            case SECURITY_NONE:
            case SECURITY_OWE:
            case SECURITY_WEP:
            case SECURITY_PSK:
            case SECURITY_SAE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns whether the user can use Easy Connect to onboard a device to the network.
     * See https://www.wi-fi.org/discover-wi-fi/wi-fi-easy-connect
     */
    @Override
    public boolean canEasyConnect() {
        if (getWifiConfiguration() == null) {
            return false;
        }

        if (!mWifiManager.isEasyConnectSupported()) {
            return false;
        }

        // DPP 1.0 only supports WPA2 and WPA3.
        switch (mSecurity) {
            case SECURITY_PSK:
            case SECURITY_SAE:
                return true;
            default:
                return false;
        }
    }

    @Override
    @MeteredChoice
    public int getMeteredChoice() {
        if (getWifiConfiguration() != null) {
            final int meteredOverride = getWifiConfiguration().meteredOverride;
            if (meteredOverride == WifiConfiguration.METERED_OVERRIDE_METERED) {
                return METERED_CHOICE_METERED;
            } else if (meteredOverride == WifiConfiguration.METERED_OVERRIDE_NOT_METERED) {
                return METERED_CHOICE_UNMETERED;
            }
        }
        return METERED_CHOICE_AUTO;
    }

    @Override
    public boolean canSetMeteredChoice() {
        return getWifiConfiguration() != null;
    }

    @Override
    public void setMeteredChoice(int meteredChoice) {
        if (!canSetMeteredChoice()) {
            return;
        }

        if (meteredChoice == METERED_CHOICE_AUTO) {
            mWifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_NONE;
        } else if (meteredChoice == METERED_CHOICE_METERED) {
            mWifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_METERED;
        } else if (meteredChoice == METERED_CHOICE_UNMETERED) {
            mWifiConfig.meteredOverride = WifiConfiguration.METERED_OVERRIDE_NOT_METERED;
        }
        mWifiManager.save(mWifiConfig, null /* listener */);
    }

    @Override
    public boolean canSetPrivacy() {
        return isSaved();
    }

    @Override
    @Privacy
    public int getPrivacy() {
        if (mWifiConfig != null
                && mWifiConfig.macRandomizationSetting == WifiConfiguration.RANDOMIZATION_NONE) {
            return PRIVACY_DEVICE_MAC;
        } else {
            return PRIVACY_RANDOMIZED_MAC;
        }
    }

    @Override
    public void setPrivacy(int privacy) {
        if (!canSetPrivacy()) {
            return;
        }

        mWifiConfig.macRandomizationSetting = privacy == PRIVACY_RANDOMIZED_MAC
                ? WifiConfiguration.RANDOMIZATION_PERSISTENT : WifiConfiguration.RANDOMIZATION_NONE;
        mWifiManager.save(mWifiConfig, null /* listener */);
    }

    @Override
    public boolean isAutoJoinEnabled() {
        if (mWifiConfig == null) {
            return false;
        }

        return mWifiConfig.allowAutojoin;
    }

    @Override
    public boolean canSetAutoJoinEnabled() {
        return isSaved() || isSuggestion();
    }

    @Override
    public void setAutoJoinEnabled(boolean enabled) {
        if (!canSetAutoJoinEnabled()) {
            return;
        }

        mWifiManager.allowAutojoin(mWifiConfig.networkId, enabled);
    }

    @Override
    public String getSecurityString(boolean concise) {
        switch(mSecurity) {
            case SECURITY_EAP:
                switch (mEapType) {
                    case EAP_WPA:
                        return concise ? mContext.getString(R.string.wifi_security_short_eap_wpa) :
                                mContext.getString(R.string.wifi_security_eap_wpa);
                    case EAP_WPA2_WPA3:
                        return concise
                                ? mContext.getString(R.string.wifi_security_short_eap_wpa2_wpa3) :
                                mContext.getString(R.string.wifi_security_eap_wpa2_wpa3);
                    case EAP_UNKNOWN:
                    default:
                        return concise ? mContext.getString(R.string.wifi_security_short_eap) :
                                mContext.getString(R.string.wifi_security_eap);
                }
            case SECURITY_EAP_SUITE_B:
                return concise ? mContext.getString(R.string.wifi_security_short_eap_suiteb) :
                        mContext.getString(R.string.wifi_security_eap_suiteb);
            case SECURITY_PSK:
                switch (mPskType) {
                    case PSK_WPA:
                        return concise ? mContext.getString(R.string.wifi_security_short_wpa) :
                            mContext.getString(R.string.wifi_security_wpa);
                    case PSK_WPA2:
                        return concise
                            ? mContext.getString(R.string.wifi_security_short_wpa2_wpa3) :
                            mContext.getString(R.string.wifi_security_wpa2_wpa3);
                    case PSK_WPA_WPA2:
                    case PSK_UNKNOWN:
                    default:
                        return concise
                            ? mContext.getString(R.string.wifi_security_short_wpa_wpa2_wpa3) :
                            mContext.getString(R.string.wifi_security_wpa_wpa2_wpa3);
                }
            case SECURITY_WEP:
                return mContext.getString(R.string.wifi_security_wep);
            case SECURITY_SAE:
                return concise ? mContext.getString(R.string.wifi_security_short_sae) :
                        mContext.getString(R.string.wifi_security_sae);
            case SECURITY_OWE:
                return concise ? mContext.getString(R.string.wifi_security_short_owe) :
                    mContext.getString(R.string.wifi_security_owe);
            case SECURITY_NONE:
            default:
                return concise ? "" : mContext.getString(R.string.wifi_security_none);
        }
    }

    @Override
    public boolean isExpired() {
        return false;
    }

    @Override
    public boolean shouldEditBeforeConnect() {
        WifiConfiguration wifiConfig = getWifiConfiguration();
        if (wifiConfig == null) {
            return false;
        }

        // The secured Wi-Fi entry is never connected.
        if (getSecurity() != SECURITY_NONE && getSecurity() != SECURITY_OWE
                && !wifiConfig.getNetworkSelectionStatus().hasEverConnected()) {
            return true;
        }

        // The network is disabled because of one of the authentication problems.
        NetworkSelectionStatus networkSelectionStatus = wifiConfig.getNetworkSelectionStatus();
        if (networkSelectionStatus.getNetworkSelectionStatus() != NETWORK_SELECTION_ENABLED) {
            if (networkSelectionStatus.getDisableReasonCounter(DISABLED_AUTHENTICATION_FAILURE) > 0
                    || networkSelectionStatus.getDisableReasonCounter(
                    DISABLED_BY_WRONG_PASSWORD) > 0
                    || networkSelectionStatus.getDisableReasonCounter(
                    DISABLED_AUTHENTICATION_NO_CREDENTIALS) > 0) {
                return true;
            }
        }

        return false;
    }

    @WorkerThread
    void updateScanResultInfo(@Nullable List<ScanResult> scanResults)
            throws IllegalArgumentException {
        if (scanResults == null) scanResults = new ArrayList<>();

        for (ScanResult result : scanResults) {
            if (!TextUtils.equals(result.SSID, mSsid)) {
                throw new IllegalArgumentException(
                        "Attempted to update with wrong SSID! Expected: "
                                + mSsid + ", Actual: " + result.SSID + ", ScanResult: " + result);
            }
        }

        synchronized (mLock) {
            mCurrentScanResults.clear();
            mCurrentScanResults.addAll(scanResults);
        }

        final ScanResult bestScanResult = getBestScanResultByLevel(scanResults);
        if (bestScanResult != null) {
            updateEapType(bestScanResult);
            updatePskType(bestScanResult);
        }

        if (getConnectedState() == CONNECTED_STATE_DISCONNECTED) {
            mLevel = bestScanResult != null
                    ? mWifiManager.calculateSignalLevel(bestScanResult.level)
                    : WIFI_LEVEL_UNREACHABLE;
            synchronized (mLock) {
                // Average speed is used to prevent speed label flickering from multiple APs.
                mSpeed = getAverageSpeedFromScanResults(mScoreCache, mCurrentScanResults);
            }
        }
        notifyOnUpdated();
    }

    @WorkerThread
    @Override
    void updateNetworkCapabilities(@Nullable NetworkCapabilities capabilities) {
        super.updateNetworkCapabilities(capabilities);

        // Auto-open an available captive portal if the user manually connected to this network.
        if (canSignIn() && mShouldAutoOpenCaptivePortal) {
            mShouldAutoOpenCaptivePortal = false;
            signIn(null /* callback */);
        }
    }

    @WorkerThread
    void onScoreCacheUpdated() {
        if (mWifiInfo != null) {
            mSpeed = getSpeedFromWifiInfo(mScoreCache, mWifiInfo);
        } else {
            synchronized (mLock) {
                // Average speed is used to prevent speed label flickering from multiple APs.
                mSpeed = getAverageSpeedFromScanResults(mScoreCache, mCurrentScanResults);
            }
        }
        notifyOnUpdated();
    }

    private void updateEapType(ScanResult result) {
        if (result.capabilities.contains("RSN-EAP")) {
            // WPA2-Enterprise and WPA3-Enterprise (non 192-bit) advertise RSN-EAP-CCMP
            mEapType = EAP_WPA2_WPA3;
        } else if (result.capabilities.contains("WPA-EAP")) {
            // WPA-Enterprise advertises WPA-EAP-TKIP
            mEapType = EAP_WPA;
        } else {
            mEapType = EAP_UNKNOWN;
        }
    }

    private void updatePskType(ScanResult result) {
        if (mSecurity != SECURITY_PSK) {
            mPskType = PSK_UNKNOWN;
            return;
        }

        final boolean wpa = result.capabilities.contains("WPA-PSK");
        final boolean wpa2 = result.capabilities.contains("RSN-PSK");
        if (wpa2 && wpa) {
            mPskType = PSK_WPA_WPA2;
        } else if (wpa2) {
            mPskType = PSK_WPA2;
        } else if (wpa) {
            mPskType = PSK_WPA;
        } else {
            mPskType = PSK_UNKNOWN;
        }
    }

    @WorkerThread
    void updateConfig(@Nullable WifiConfiguration wifiConfig) throws IllegalArgumentException {
        if (wifiConfig != null) {
            if (!TextUtils.equals(mSsid, sanitizeSsid(wifiConfig.SSID))) {
                throw new IllegalArgumentException(
                        "Attempted to update with wrong SSID!"
                                + " Expected: " + mSsid
                                + ", Actual: " + sanitizeSsid(wifiConfig.SSID)
                                + ", Config: " + wifiConfig);
            }
            if (mSecurity != getSecurityTypeFromWifiConfiguration(wifiConfig)) {
                throw new IllegalArgumentException(
                        "Attempted to update with wrong security!"
                                + " Expected: " + mSecurity
                                + ", Actual: " + getSecurityTypeFromWifiConfiguration(wifiConfig)
                                + ", Config: " + wifiConfig);
            }
        }

        mWifiConfig = wifiConfig;
        notifyOnUpdated();
    }

    /**
     * Sets whether the suggested config for this entry is shareable to the user or not.
     */
    @WorkerThread
    void setUserShareable(boolean isUserShareable) {
        mIsUserShareable = isUserShareable;
    }

    /**
     * Returns whether the suggested config for this entry is shareable to the user or not.
     */
    @WorkerThread
    boolean isUserShareable() {
        return mIsUserShareable;
    }

    @WorkerThread
    protected boolean connectionInfoMatches(@NonNull WifiInfo wifiInfo,
            @NonNull NetworkInfo networkInfo) {
        if (wifiInfo.isPasspointAp() || wifiInfo.isOsuAp()) {
            return false;
        }

        if (mWifiConfig != null) {
            if (mWifiConfig.networkId == wifiInfo.getNetworkId()) {
                return true;
            }
        }
        return false;
    }

    private void updateRecommendationServiceLabel() {
        final NetworkScorerAppData scorer = ((NetworkScoreManager) mContext
                .getSystemService(Context.NETWORK_SCORE_SERVICE)).getActiveScorer();
        if (scorer != null) {
            mRecommendationServiceLabel = scorer.getRecommendationServiceLabel();
        }
    }

    @NonNull
    static String ssidAndSecurityToStandardWifiEntryKey(@NonNull String ssid,
            @Security int security) {
        return KEY_PREFIX + ssid + "," + security;
    }

    @NonNull
    static String wifiConfigToStandardWifiEntryKey(@NonNull WifiConfiguration config) {
        checkNotNull(config, "Cannot create key with null config!");
        checkNotNull(config.SSID, "Cannot create key with null SSID in config!");
        return KEY_PREFIX + sanitizeSsid(config.SSID) + ","
                + getSecurityTypeFromWifiConfiguration(config);
    }

    @Override
    String getScanResultDescription() {
        synchronized (mLock) {
            if (mCurrentScanResults.size() == 0) {
                return "";
            }
        }

        final StringBuilder description = new StringBuilder();
        description.append("[");
        description.append(getScanResultDescription(MIN_FREQ_24GHZ, MAX_FREQ_24GHZ)).append(";");
        description.append(getScanResultDescription(MIN_FREQ_5GHZ, MAX_FREQ_5GHZ)).append(";");
        description.append(getScanResultDescription(MIN_FREQ_6GHZ, MAX_FREQ_6GHZ));
        description.append("]");
        return description.toString();
    }

    private String getScanResultDescription(int minFrequency, int maxFrequency) {
        final List<ScanResult> scanResults;
        synchronized (mLock) {
            scanResults = mCurrentScanResults.stream()
                    .filter(scanResult -> scanResult.frequency >= minFrequency
                            && scanResult.frequency <= maxFrequency)
                    .sorted(Comparator.comparingInt(scanResult -> -1 * scanResult.level))
                    .collect(Collectors.toList());
        }

        final int scanResultCount = scanResults.size();
        if (scanResultCount == 0) {
            return "";
        }

        final StringBuilder description = new StringBuilder();
        description.append("(").append(scanResultCount).append(")");
        if (scanResultCount > MAX_VERBOSE_LOG_DISPLAY_SCANRESULT_COUNT) {
            final int maxLavel = scanResults.stream()
                    .mapToInt(scanResult -> scanResult.level).max().getAsInt();
            description.append("max=").append(maxLavel).append(",");
        }
        final long nowMs = SystemClock.elapsedRealtime();
        scanResults.forEach(scanResult ->
                description.append(getScanResultDescription(scanResult, nowMs)));
        return description.toString();
    }

    private String getScanResultDescription(ScanResult scanResult, long nowMs) {
        final StringBuilder description = new StringBuilder();
        description.append(" \n{");
        description.append(scanResult.BSSID);
        if (mWifiInfo != null && scanResult.BSSID.equals(mWifiInfo.getBSSID())) {
            description.append("*");
        }
        description.append("=").append(scanResult.frequency);
        description.append(",").append(scanResult.level);
        final int ageSeconds = (int) (nowMs - scanResult.timestamp / 1000) / 1000;
        description.append(",").append(ageSeconds).append("s");
        description.append("}");
        return description.toString();
    }

    @Override
    String getNetworkSelectionDescription() {
        return Utils.getNetworkSelectionDescription(getWifiConfiguration());
    }
}
