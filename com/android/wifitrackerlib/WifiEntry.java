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

import static android.net.wifi.WifiInfo.INVALID_RSSI;

import static androidx.core.util.Preconditions.checkNotNull;

import static com.android.wifitrackerlib.Utils.getSpeedFromWifiInfo;

import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkScoreCache;
import android.os.Handler;

import androidx.annotation.AnyThread;
import androidx.annotation.IntDef;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Abstract base class for an entry representing a Wi-Fi network in a Wi-Fi picker/settings.
 *
 * Clients implementing a Wi-Fi picker/settings should receive WifiEntry objects from classes
 * implementing BaseWifiTracker, and rely on the given API for all user-displayable information and
 * actions on the represented network.
 */
public abstract class WifiEntry implements Comparable<WifiEntry> {
    /**
     * Security type based on WifiConfiguration.KeyMgmt
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            SECURITY_NONE,
            SECURITY_OWE,
            SECURITY_WEP,
            SECURITY_PSK,
            SECURITY_SAE,
            SECURITY_EAP,
            SECURITY_EAP_SUITE_B,
    })

    public @interface Security {}

    public static final int SECURITY_NONE = 0;
    public static final int SECURITY_WEP = 1;
    public static final int SECURITY_PSK = 2;
    public static final int SECURITY_EAP = 3;
    public static final int SECURITY_OWE = 4;
    public static final int SECURITY_SAE = 5;
    public static final int SECURITY_EAP_SUITE_B = 6;

    public static final int NUM_SECURITY_TYPES = 7;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            CONNECTED_STATE_DISCONNECTED,
            CONNECTED_STATE_CONNECTED,
            CONNECTED_STATE_CONNECTING
    })

    public @interface ConnectedState {}

    public static final int CONNECTED_STATE_DISCONNECTED = 0;
    public static final int CONNECTED_STATE_CONNECTING = 1;
    public static final int CONNECTED_STATE_CONNECTED = 2;

    // Wi-Fi signal levels for displaying signal strength.
    public static final int WIFI_LEVEL_MIN = 0;
    public static final int WIFI_LEVEL_MAX = 4;
    public static final int WIFI_LEVEL_UNREACHABLE = -1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            SPEED_NONE,
            SPEED_SLOW,
            SPEED_MODERATE,
            SPEED_FAST,
            SPEED_VERY_FAST
    })

    public @interface Speed {}

    public static final int SPEED_NONE = 0;
    public static final int SPEED_SLOW = 5;
    public static final int SPEED_MODERATE = 10;
    public static final int SPEED_FAST = 20;
    public static final int SPEED_VERY_FAST = 30;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            METERED_CHOICE_AUTO,
            METERED_CHOICE_METERED,
            METERED_CHOICE_UNMETERED,
    })

    public @interface MeteredChoice {}

    // User's choice whether to treat a network as metered.
    public static final int METERED_CHOICE_AUTO = 0;
    public static final int METERED_CHOICE_METERED = 1;
    public static final int METERED_CHOICE_UNMETERED = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            PRIVACY_DEVICE_MAC,
            PRIVACY_RANDOMIZED_MAC,
            PRIVACY_UNKNOWN
    })

    public @interface Privacy {}

    public static final int PRIVACY_DEVICE_MAC = 0;
    public static final int PRIVACY_RANDOMIZED_MAC = 1;
    public static final int PRIVACY_UNKNOWN = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            FREQUENCY_2_4_GHZ,
            FREQUENCY_5_GHZ,
            FREQUENCY_6_GHZ,
            FREQUENCY_UNKNOWN
    })

    public @interface Frequency {}

    public static final int FREQUENCY_2_4_GHZ = 2_400;
    public static final int FREQUENCY_5_GHZ = 5_000;
    public static final int FREQUENCY_6_GHZ = 6_000;
    public static final int FREQUENCY_UNKNOWN = -1;

    /**
     * Min bound on the 2.4 GHz (802.11b/g/n) WLAN channels.
     */
    public static final int MIN_FREQ_24GHZ = 2400;

    /**
     * Max bound on the 2.4 GHz (802.11b/g/n) WLAN channels.
     */
    public static final int MAX_FREQ_24GHZ = 2500;

    /**
     * Min bound on the 5.0 GHz (802.11a/h/j/n/ac) WLAN channels.
     */
    public static final int MIN_FREQ_5GHZ = 4900;

    /**
     * Max bound on the 5.0 GHz (802.11a/h/j/n/ac) WLAN channels.
     */
    public static final int MAX_FREQ_5GHZ = 5900;

    /**
     * Min bound on the 6.0 GHz (802.11ax) WLAN channels.
     */
    public static final int MIN_FREQ_6GHZ = 5925;

    /**
     * Max bound on the 6.0 GHz (802.11ax) WLAN channels.
     */
    public static final int MAX_FREQ_6GHZ = 7125;

    /**
     * Max ScanResult information displayed of Wi-Fi Verbose Logging.
     */
    protected static final int MAX_VERBOSE_LOG_DISPLAY_SCANRESULT_COUNT = 4;

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    final boolean mForSavedNetworksPage;

    protected final WifiManager mWifiManager;

    // Callback associated with this WifiEntry. Subclasses should call its methods appropriately.
    private WifiEntryCallback mListener;
    protected Handler mCallbackHandler;

    protected int mLevel = WIFI_LEVEL_UNREACHABLE;
    protected int mSpeed = SPEED_NONE;
    protected WifiInfo mWifiInfo;
    protected NetworkInfo mNetworkInfo;
    protected NetworkCapabilities mNetworkCapabilities;
    protected ConnectedInfo mConnectedInfo;
    protected WifiNetworkScoreCache mScoreCache;

    protected ConnectCallback mConnectCallback;
    protected DisconnectCallback mDisconnectCallback;
    protected ForgetCallback mForgetCallback;

    protected boolean mCalledConnect = false;
    protected boolean mCalledDisconnect = false;

    WifiEntry(@NonNull Handler callbackHandler, @NonNull WifiManager wifiManager,
            @NonNull WifiNetworkScoreCache scoreCache,
            boolean forSavedNetworksPage) throws IllegalArgumentException {
        checkNotNull(callbackHandler, "Cannot construct with null handler!");
        checkNotNull(wifiManager, "Cannot construct with null WifiManager!");
        mCallbackHandler = callbackHandler;
        mForSavedNetworksPage = forSavedNetworksPage;
        mWifiManager = wifiManager;
        mScoreCache = scoreCache;
    }

    // Info available for all WifiEntries //

    /** The unique key defining a WifiEntry */
    public abstract String getKey();

    /** Returns connection state of the network defined by the CONNECTED_STATE constants */
    @ConnectedState
    public int getConnectedState() {
        if (mNetworkInfo == null) {
            return CONNECTED_STATE_DISCONNECTED;
        }

        switch (mNetworkInfo.getDetailedState()) {
            case SCANNING:
            case CONNECTING:
            case AUTHENTICATING:
            case OBTAINING_IPADDR:
            case VERIFYING_POOR_LINK:
            case CAPTIVE_PORTAL_CHECK:
                return CONNECTED_STATE_CONNECTING;
            case CONNECTED:
                return CONNECTED_STATE_CONNECTED;
            default:
                return CONNECTED_STATE_DISCONNECTED;
        }
    }


    /** Returns the display title. This is most commonly the SSID of a network. */
    public abstract String getTitle();

    /** Returns the display summary, it's a concise summary. */
    public String getSummary() {
        return getSummary(true /* concise */);
    }

    /** Returns the second summary, it's for additional information of the WifiEntry */
    public CharSequence getSecondSummary() {
        return "";
    }

    /**
     * Returns the display summary.
     * @param concise Whether to show more information. e.g., verbose logging.
     */
    public abstract String getSummary(boolean concise);

    /**
     * Returns the signal strength level within [WIFI_LEVEL_MIN, WIFI_LEVEL_MAX].
     * A value of WIFI_LEVEL_UNREACHABLE indicates an out of range network.
     */
    public int getLevel() {
        return mLevel;
    };

    /** Returns the speed value of the network defined by the SPEED constants */
    @Speed
    public int getSpeed() {
        return mSpeed;
    };

    /**
     * Returns the SSID of the entry, if applicable. Null otherwise.
     */
    public abstract String getSsid();

    /** Returns the security type defined by the SECURITY constants */
    @Security
    public abstract int getSecurity();

    /** Returns the MAC address of the connection */
    public abstract String getMacAddress();

    /**
     * Indicates when a network is metered or the user marked the network as metered.
     */
    public abstract boolean isMetered();

    /**
     * Indicates whether or not an entry is for a saved configuration.
     */
    public abstract boolean isSaved();

    /**
     * Indicates whether or not an entry is for a saved configuration.
     */
    public abstract boolean isSuggestion();

    /**
     * Indicates whether or not an entry is for a subscription.
     */
    public abstract boolean isSubscription();

    /**
     * Returns the WifiConfiguration of an entry or null if unavailable. This should be used when
     * information on the WifiConfiguration needs to be modified and saved via
     * {@link WifiManager#save(WifiConfiguration, WifiManager.ActionListener)}.
     */
    public abstract WifiConfiguration getWifiConfiguration();

    /**
     * Returns the ConnectedInfo object pertaining to an active connection.
     *
     * Returns null if getConnectedState() != CONNECTED_STATE_CONNECTED.
     */
    public ConnectedInfo getConnectedInfo() {
        if (getConnectedState() != CONNECTED_STATE_CONNECTED) {
            return null;
        }

        return mConnectedInfo;
    }

    /**
     * Info associated with the active connection.
     */
    public static class ConnectedInfo {
        @Frequency
        public int frequencyMhz;
        public List<String> dnsServers = new ArrayList<>();
        public int linkSpeedMbps;
        public String ipAddress;
        public List<String> ipv6Addresses = new ArrayList<>();
        public String gateway;
        public String subnetMask;
    }

    // User actions on a network

    /** Returns whether the entry should show a connect option */
    public abstract boolean canConnect();
    /** Connects to the network */
    public abstract void connect(@Nullable ConnectCallback callback);

    /** Returns whether the entry should show a disconnect option */
    public abstract boolean canDisconnect();
    /** Disconnects from the network */
    public abstract void disconnect(@Nullable DisconnectCallback callback);

    /** Returns whether the entry should show a forget option */
    public abstract boolean canForget();
    /** Forgets the network */
    public abstract void forget(@Nullable ForgetCallback callback);

    /** Returns whether the network can be signed-in to */
    public abstract boolean canSignIn();
    /** Sign-in to the network. For captive portals. */
    public abstract void signIn(@Nullable SignInCallback callback);

    /** Returns whether the network can be shared via QR code */
    public abstract boolean canShare();
    /** Returns whether the user can use Easy Connect to onboard a device to the network */
    public abstract boolean canEasyConnect();

    // Modifiable settings

    /**
     *  Returns the user's choice whether to treat a network as metered,
     *  defined by the METERED_CHOICE constants
     */
    @MeteredChoice
    public abstract int getMeteredChoice();
    /** Returns whether the entry should let the user choose the metered treatment of a network */
    public abstract boolean canSetMeteredChoice();
    /**
     * Sets the user's choice for treating a network as metered,
     * defined by the METERED_CHOICE constants
     */
    public abstract void setMeteredChoice(@MeteredChoice int meteredChoice);

    /** Returns whether the entry should let the user choose the MAC randomization setting */
    public abstract boolean canSetPrivacy();
    /** Returns the MAC randomization setting defined by the PRIVACY constants */
    @Privacy
    public abstract int getPrivacy();
    /** Sets the user's choice for MAC randomization defined by the PRIVACY constants */
    public abstract void setPrivacy(@Privacy int privacy);

    /** Returns whether the network has auto-join enabled */
    public abstract boolean isAutoJoinEnabled();
    /** Returns whether the user can enable/disable auto-join */
    public abstract boolean canSetAutoJoinEnabled();
    /** Sets whether a network will be auto-joined or not */
    public abstract void setAutoJoinEnabled(boolean enabled);
    /** Returns the string displayed for @Security */
    public abstract String getSecurityString(boolean concise);
    /** Returns whether subscription of the entry is expired */
    public abstract boolean isExpired();

    /** Returns whether a user can manage their subscription through this WifiEntry */
    public boolean canManageSubscription() {
        // Subclasses should implement this method.
        return false;
    };

    /**
     * Return the URI string value of help, if it is not null, WifiPicker may show
     * help icon and route the user to help page specified by the URI string.
     * see {@link Intent#parseUri}
     */
    @Nullable
    public String getHelpUriString() {
        return null;
    }

    /** Allows the user to manage their subscription via an external flow */
    public void manageSubscription() {
        // Subclasses should implement this method.
    };

    /** Returns the ScanResult information of a WifiEntry */
    abstract String getScanResultDescription();

    /** Returns the network selection information of a WifiEntry */
    String getNetworkSelectionDescription() {
        return "";
    }

    /**
     * In Wi-Fi picker, when users click a saved network, it will connect to the Wi-Fi network.
     * However, for some special cases, Wi-Fi picker should show Wi-Fi editor UI for users to edit
     * security or password before connecting. Or users will always get connection fail results.
     */
    public boolean shouldEditBeforeConnect() {
        return false;
    }

    /**
     * Sets the callback listener for WifiEntryCallback methods.
     * Subsequent calls will overwrite the previous listener.
     */
    public void setListener(WifiEntryCallback listener) {
        mListener = listener;
    }

    /**
     * Listener for changes to the state of the WifiEntry.
     * This callback will be invoked on the main thread.
     */
    public interface WifiEntryCallback {
        /**
         * Indicates the state of the WifiEntry has changed and clients may retrieve updates through
         * the WifiEntry getter methods.
         */
        @MainThread
        void onUpdated();
    }

    @AnyThread
    protected void notifyOnUpdated() {
        if (mListener != null) {
            mCallbackHandler.post(() -> mListener.onUpdated());
        }
    }

    /**
     * Listener for changes to the state of the WifiEntry.
     * This callback will be invoked on the main thread.
     */
    public interface ConnectCallback {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                CONNECT_STATUS_SUCCESS,
                CONNECT_STATUS_FAILURE_NO_CONFIG,
                CONNECT_STATUS_FAILURE_UNKNOWN
        })

        public @interface ConnectStatus {}

        int CONNECT_STATUS_SUCCESS = 0;
        int CONNECT_STATUS_FAILURE_NO_CONFIG = 1;
        int CONNECT_STATUS_FAILURE_UNKNOWN = 2;

        /**
         * Result of the connect request indicated by the CONNECT_STATUS constants.
         */
        @MainThread
        void onConnectResult(@ConnectStatus int status);
    }

    /**
     * Listener for changes to the state of the WifiEntry.
     * This callback will be invoked on the main thread.
     */
    public interface DisconnectCallback {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                DISCONNECT_STATUS_SUCCESS,
                DISCONNECT_STATUS_FAILURE_UNKNOWN
        })

        public @interface DisconnectStatus {}

        int DISCONNECT_STATUS_SUCCESS = 0;
        int DISCONNECT_STATUS_FAILURE_UNKNOWN = 1;
        /**
         * Result of the disconnect request indicated by the DISCONNECT_STATUS constants.
         */
        @MainThread
        void onDisconnectResult(@DisconnectStatus int status);
    }

    /**
     * Listener for changes to the state of the WifiEntry.
     * This callback will be invoked on the main thread.
     */
    public interface ForgetCallback {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                FORGET_STATUS_SUCCESS,
                FORGET_STATUS_FAILURE_UNKNOWN
        })

        public @interface ForgetStatus {}

        int FORGET_STATUS_SUCCESS = 0;
        int FORGET_STATUS_FAILURE_UNKNOWN = 1;

        /**
         * Result of the forget request indicated by the FORGET_STATUS constants.
         */
        @MainThread
        void onForgetResult(@ForgetStatus int status);
    }

    /**
     * Listener for changes to the state of the WifiEntry.
     * This callback will be invoked on the main thread.
     */
    public interface SignInCallback {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = {
                SIGNIN_STATUS_SUCCESS,
                SIGNIN_STATUS_FAILURE_UNKNOWN
        })

        public @interface SignInStatus {}

        int SIGNIN_STATUS_SUCCESS = 0;
        int SIGNIN_STATUS_FAILURE_UNKNOWN = 1;

        /**
         * Result of the sign-in request indicated by the SIGNIN_STATUS constants.
         */
        @MainThread
        void onSignInResult(@SignInStatus int status);
    }

    /**
     * Returns whether or not the supplied WifiInfo and NetworkInfo represent this WifiEntry
     */
    protected abstract boolean connectionInfoMatches(@NonNull WifiInfo wifiInfo,
            @NonNull NetworkInfo networkInfo);

    /**
     * Updates information regarding the current network connection. If the supplied WifiInfo and
     * NetworkInfo do not match this WifiEntry, then the WifiEntry will update to be
     * unconnected.
     */
    @WorkerThread
    void updateConnectionInfo(@Nullable WifiInfo wifiInfo, @Nullable NetworkInfo networkInfo) {
        if (wifiInfo != null && networkInfo != null
                && connectionInfoMatches(wifiInfo, networkInfo)) {
            // Connection info matches, so the WifiInfo/NetworkInfo represent this network and
            // the network is currently connecting or connected.
            mWifiInfo = wifiInfo;
            mNetworkInfo = networkInfo;
            final int wifiInfoRssi = wifiInfo.getRssi();
            if (wifiInfoRssi != INVALID_RSSI) {
                mLevel = mWifiManager.calculateSignalLevel(wifiInfoRssi);
                mSpeed = getSpeedFromWifiInfo(mScoreCache, wifiInfo);
            }
            if (getConnectedState() == CONNECTED_STATE_CONNECTED) {
                if (mCalledConnect) {
                    mCalledConnect = false;
                    mCallbackHandler.post(() -> {
                        if (mConnectCallback != null) {
                            mConnectCallback.onConnectResult(
                                    ConnectCallback.CONNECT_STATUS_SUCCESS);
                        }
                    });
                }

                if (mConnectedInfo == null) {
                    mConnectedInfo = new ConnectedInfo();
                }
                mConnectedInfo.frequencyMhz = wifiInfo.getFrequency();
                mConnectedInfo.linkSpeedMbps = wifiInfo.getLinkSpeed();
            }
        } else { // Connection info doesn't matched, so this network is disconnected
            mNetworkInfo = null;
            mNetworkCapabilities = null;
            mConnectedInfo = null;
            if (mCalledDisconnect) {
                mCalledDisconnect = false;
                mCallbackHandler.post(() -> {
                    if (mDisconnectCallback != null) {
                        mDisconnectCallback.onDisconnectResult(
                                DisconnectCallback.DISCONNECT_STATUS_SUCCESS);
                    }
                });
            }
        }
        notifyOnUpdated();
    }

    // Method for WifiTracker to update the link properties, which is valid for all WifiEntry types.
    @WorkerThread
    void updateLinkProperties(@Nullable LinkProperties linkProperties) {
        if (linkProperties == null || getConnectedState() != CONNECTED_STATE_CONNECTED) {
            mConnectedInfo = null;
            notifyOnUpdated();
            return;
        }

        if (mConnectedInfo == null) {
            mConnectedInfo = new ConnectedInfo();
        }
        // Find IPv4 and IPv6 addresses, and subnet mask
        List<String> ipv6Addresses = new ArrayList<>();
        for (LinkAddress addr : linkProperties.getLinkAddresses()) {
            if (addr.getAddress() instanceof Inet4Address) {
                mConnectedInfo.ipAddress = addr.getAddress().getHostAddress();
                try {
                    InetAddress all = InetAddress.getByAddress(
                            new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255});
                    mConnectedInfo.subnetMask = NetworkUtils.getNetworkPart(
                            all, addr.getPrefixLength()).getHostAddress();
                } catch (UnknownHostException e) {
                    // Leave subnet null;
                }
            } else if (addr.getAddress() instanceof Inet6Address) {
                ipv6Addresses.add(addr.getAddress().getHostAddress());
            }
        }
        mConnectedInfo.ipv6Addresses = ipv6Addresses;

        // Find IPv4 default gateway.
        for (RouteInfo routeInfo : linkProperties.getRoutes()) {
            if (routeInfo.isIPv4Default() && routeInfo.hasGateway()) {
                mConnectedInfo.gateway = routeInfo.getGateway().getHostAddress();
                break;
            }
        }

        // Find DNS servers
        mConnectedInfo.dnsServers = linkProperties.getDnsServers().stream()
                .map(InetAddress::getHostAddress).collect(Collectors.toList());

        notifyOnUpdated();
    }

    // Method for WifiTracker to update a connected WifiEntry's network capabilities.
    @WorkerThread
    void updateNetworkCapabilities(@Nullable NetworkCapabilities capabilities) {
        mNetworkCapabilities = capabilities;
    }

    String getWifiInfoDescription() {
        final StringJoiner sj = new StringJoiner(" ");
        if (getConnectedState() == CONNECTED_STATE_CONNECTED && mWifiInfo != null) {
            sj.add("f = " + mWifiInfo.getFrequency());
            final String bssid = mWifiInfo.getBSSID();
            if (bssid != null) {
                sj.add(bssid);
            }
            sj.add("standard = " + mWifiInfo.getWifiStandard());
            sj.add("rssi = " + mWifiInfo.getRssi());
            sj.add("score = " + mWifiInfo.getScore());
            sj.add(String.format(" tx=%.1f,", mWifiInfo.getSuccessfulTxPacketsPerSecond()));
            sj.add(String.format("%.1f,", mWifiInfo.getRetriedTxPacketsPerSecond()));
            sj.add(String.format("%.1f ", mWifiInfo.getLostTxPacketsPerSecond()));
            sj.add(String.format("rx=%.1f", mWifiInfo.getSuccessfulRxPacketsPerSecond()));
        }
        return sj.toString();
    }

    protected class ConnectActionListener implements WifiManager.ActionListener {
        @Override
        public void onSuccess() {
            mCalledConnect = true;
            // If we aren't connected to the network after 10 seconds, trigger the failure callback
            mCallbackHandler.postDelayed(() -> {
                if (mConnectCallback != null && mCalledConnect
                        && getConnectedState() == CONNECTED_STATE_DISCONNECTED) {
                    mConnectCallback.onConnectResult(
                            ConnectCallback.CONNECT_STATUS_FAILURE_UNKNOWN);
                    mCalledConnect = false;
                }
            }, 10_000 /* delayMillis */);
        }

        @Override
        public void onFailure(int i) {
            mCallbackHandler.post(() -> {
                if (mConnectCallback != null) {
                    mConnectCallback.onConnectResult(
                            mConnectCallback.CONNECT_STATUS_FAILURE_UNKNOWN);
                }
            });
        }
    }

    protected class ForgetActionListener implements WifiManager.ActionListener {
        @Override
        public void onSuccess() {
            mCallbackHandler.post(() -> {
                if (mForgetCallback != null) {
                    mForgetCallback.onForgetResult(ForgetCallback.FORGET_STATUS_SUCCESS);
                }
            });
        }

        @Override
        public void onFailure(int i) {
            mCallbackHandler.post(() -> {
                if (mForgetCallback != null) {
                    mForgetCallback.onForgetResult(ForgetCallback.FORGET_STATUS_FAILURE_UNKNOWN);
                }
            });
        }
    }

    @Override
    public int compareTo(@NonNull WifiEntry other) {
        if (getLevel() != WIFI_LEVEL_UNREACHABLE && other.getLevel() == WIFI_LEVEL_UNREACHABLE) {
            return -1;
        }
        if (getLevel() == WIFI_LEVEL_UNREACHABLE && other.getLevel() != WIFI_LEVEL_UNREACHABLE) {
            return 1;
        }

        if (isSubscription() && !other.isSubscription()) return -1;
        if (!isSubscription() && other.isSubscription()) return 1;

        if (isSaved() && !other.isSaved()) return -1;
        if (!isSaved() && other.isSaved()) return 1;

        if (isSuggestion() && !other.isSuggestion()) return -1;
        if (!isSuggestion() && other.isSuggestion()) return 1;

        if (getLevel() > other.getLevel()) return -1;
        if (getLevel() < other.getLevel()) return 1;

        return getTitle().compareTo(other.getTitle());
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof WifiEntry)) return false;
        return getKey().equals(((WifiEntry) other).getKey());
    }

    @Override
    public String toString() {
        return new StringBuilder()
                .append(getKey())
                .append(",title:")
                .append(getTitle())
                .append(",summary:")
                .append(getSummary())
                .append(",isSaved:")
                .append(isSaved())
                .append(",isSubscription:")
                .append(isSubscription())
                .append(",isSuggestion:")
                .append(isSuggestion())
                .append(",level:")
                .append(getLevel())
                .append(",security:")
                .append(getSecurity())
                .append(",connected:")
                .append(getConnectedState() == CONNECTED_STATE_CONNECTED ? "true" : "false")
                .append(",connectedInfo:")
                .append(getConnectedInfo())
                .toString();
    }
}
