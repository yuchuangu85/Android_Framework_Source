/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.net.wifi.WifiConfiguration.NetworkSelectionStatus.DISABLE_REASON_INFOS;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.IpConfiguration;
import android.net.MacAddress;
import android.net.ProxyInfo;
import android.net.StaticIpConfiguration;
import android.net.util.MacAddressUtils;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus.DisableReasonInfo;
import android.net.wifi.WifiConfiguration.NetworkSelectionStatus.NetworkSelectionDisableReason;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.os.Handler;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.hotspot2.PasspointManager;
import com.android.server.wifi.proto.nano.WifiMetricsProto.UserActionEvent;
import com.android.server.wifi.util.LruConnectionTracker;
import com.android.server.wifi.util.MissingCounterTimerLockList;
import com.android.server.wifi.util.WifiPermissionsUtil;
import com.android.server.wifi.util.WifiPermissionsWrapper;
import com.android.wifi.resources.R;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class provides the APIs to manage configured Wi-Fi networks.
 * It deals with the following:
 * - Maintaining a list of configured networks for quick access.
 * - Persisting the configurations to store when required.
 * - Supporting WifiManager Public API calls:
 *   > addOrUpdateNetwork()
 *   > removeNetwork()
 *   > enableNetwork()
 *   > disableNetwork()
 * - Handle user switching on multi-user devices.
 *
 * All network configurations retrieved from this class are copies of the original configuration
 * stored in the internal database. So, any updates to the retrieved configuration object are
 * meaningless and will not be reflected in the original database.
 * This is done on purpose to ensure that only WifiConfigManager can modify configurations stored
 * in the internal database. Any configuration updates should be triggered with appropriate helper
 * methods of this class using the configuration's unique networkId.
 *
 * NOTE: These API's are not thread safe and should only be used from the main Wifi thread.
 */
public class WifiConfigManager {
    /**
     * String used to mask passwords to public interface.
     */
    @VisibleForTesting
    public static final String PASSWORD_MASK = "*";

    /**
     * Interface for other modules to listen to the network updated events.
     * Note: Credentials are masked to avoid accidentally sending credentials outside the stack.
     * Use WifiConfigManager#getConfiguredNetworkWithPassword() to retrieve credentials.
     */
    public interface OnNetworkUpdateListener {
        /**
         * Invoked on network being added.
         */
        void onNetworkAdded(@NonNull WifiConfiguration config);
        /**
         * Invoked on network being enabled.
         */
        void onNetworkEnabled(@NonNull WifiConfiguration config);
        /**
         * Invoked on network being permanently disabled.
         */
        void onNetworkPermanentlyDisabled(@NonNull WifiConfiguration config, int disableReason);
        /**
         * Invoked on network being removed.
         */
        void onNetworkRemoved(@NonNull WifiConfiguration config);
        /**
         * Invoked on network being temporarily disabled.
         */
        void onNetworkTemporarilyDisabled(@NonNull WifiConfiguration config, int disableReason);
        /**
         * Invoked on network being updated.
         *
         * @param newConfig Updated WifiConfiguration object.
         * @param oldConfig Prev WifiConfiguration object.
         */
        void onNetworkUpdated(
                @NonNull WifiConfiguration newConfig, @NonNull WifiConfiguration oldConfig);
    }
    /**
     * Max size of scan details to cache in {@link #mScanDetailCaches}.
     */
    @VisibleForTesting
    public static final int SCAN_CACHE_ENTRIES_MAX_SIZE = 192;
    /**
     * Once the size of the scan details in the cache {@link #mScanDetailCaches} exceeds
     * {@link #SCAN_CACHE_ENTRIES_MAX_SIZE}, trim it down to this value so that we have some
     * buffer time before the next eviction.
     */
    @VisibleForTesting
    public static final int SCAN_CACHE_ENTRIES_TRIM_SIZE = 128;
    /**
     * Link networks only if they have less than this number of scan cache entries.
     */
    @VisibleForTesting
    public static final int LINK_CONFIGURATION_MAX_SCAN_CACHE_ENTRIES = 6;
    /**
     * Link networks only if the bssid in scan results for the networks match in the first
     * 16 ASCII chars in the bssid string. For example = "af:de:56;34:15:7"
     */
    @VisibleForTesting
    public static final int LINK_CONFIGURATION_BSSID_MATCH_LENGTH = 16;
    /**
     * Log tag for this class.
     */
    private static final String TAG = "WifiConfigManager";
    /**
     * Maximum age of scan results that can be used for averaging out RSSI value.
     */
    private static final int SCAN_RESULT_MAXIMUM_AGE_MS = 40000;

    /**
     * Maximum number of blocked BSSIDs per SSID used for calcualting the duration of temporarily
     * disabling a network.
     */
    private static final int MAX_BLOCKED_BSSID_PER_NETWORK = 10;

    /**
     * Enforce a minimum time to wait after the last disconnect to generate a new randomized MAC,
     * since IPv6 networks don't provide the DHCP lease duration.
     * 4 hours.
     */
    @VisibleForTesting
    protected static final long AGGRESSIVE_MAC_WAIT_AFTER_DISCONNECT_MS = 4 * 60 * 60 * 1000;
    @VisibleForTesting
    protected static final long AGGRESSIVE_MAC_REFRESH_MS_MIN = 30 * 60 * 1000; // 30 minutes
    @VisibleForTesting
    protected static final long AGGRESSIVE_MAC_REFRESH_MS_MAX = 24 * 60 * 60 * 1000; // 24 hours

    private static final MacAddress DEFAULT_MAC_ADDRESS =
            MacAddress.fromString(WifiInfo.DEFAULT_MAC_ADDRESS);

    /**
     * Expiration timeout for user disconnect network. (1 hour)
     */
    @VisibleForTesting
    public static final long USER_DISCONNECT_NETWORK_BLOCK_EXPIRY_MS = (long) 1000 * 60 * 60;

    @VisibleForTesting
    public static final int SCAN_RESULT_MISSING_COUNT_THRESHOLD = 1;
    @VisibleForTesting
    protected static final String ENHANCED_MAC_RANDOMIZATION_FEATURE_FORCE_ENABLE_FLAG =
            "enhanced_mac_randomization_force_enabled";

    /**
     * General sorting algorithm of all networks for scanning purposes:
     * Place the configurations in ascending order of their AgeIndex. AgeIndex is based on most
     * recently connected order. The lower the more recently connected.
     * If networks have the same AgeIndex, place the configurations with
     * |lastSeenInQualifiedNetworkSelection| set first.
     */
    private final WifiConfigurationUtil.WifiConfigurationComparator mScanListComparator =
            new WifiConfigurationUtil.WifiConfigurationComparator() {
                @Override
                public int compareNetworksWithSameStatus(WifiConfiguration a, WifiConfiguration b) {
                    int indexA = mLruConnectionTracker.getAgeIndexOfNetwork(a);
                    int indexB = mLruConnectionTracker.getAgeIndexOfNetwork(b);
                    if (indexA != indexB) {
                        return Integer.compare(indexA, indexB);
                    } else {
                        boolean isConfigALastSeen =
                                a.getNetworkSelectionStatus()
                                        .getSeenInLastQualifiedNetworkSelection();
                        boolean isConfigBLastSeen =
                                b.getNetworkSelectionStatus()
                                        .getSeenInLastQualifiedNetworkSelection();
                        return Boolean.compare(isConfigBLastSeen, isConfigALastSeen);
                    }
                }
            };

    /**
     * List of external dependencies for WifiConfigManager.
     */
    private final Context mContext;
    private final Clock mClock;
    private final UserManager mUserManager;
    private final BackupManagerProxy mBackupManagerProxy;
    private final WifiKeyStore mWifiKeyStore;
    private final WifiConfigStore mWifiConfigStore;
    private final WifiPermissionsUtil mWifiPermissionsUtil;
    private final WifiPermissionsWrapper mWifiPermissionsWrapper;
    private final WifiInjector mWifiInjector;
    private final MacAddressUtil mMacAddressUtil;
    private final WifiCarrierInfoManager mWifiCarrierInfoManager;
    private final WifiScoreCard mWifiScoreCard;
    // Keep order of network connection.
    private final LruConnectionTracker mLruConnectionTracker;

    /**
     * Local log used for debugging any WifiConfigManager issues.
     */
    private final LocalLog mLocalLog;
    /**
     * Map of configured networks with network id as the key.
     */
    private final ConfigurationMap mConfiguredNetworks;
    /**
     * Stores a map of NetworkId to ScanDetailCache.
     */
    private final Map<Integer, ScanDetailCache> mScanDetailCaches;
    /**
     * Framework keeps a list of networks that where temporarily disabled by user,
     * framework knows not to autoconnect again even if the app/scorer recommends it.
     * Network will be based on FQDN for passpoint and SSID for non-passpoint.
     * List will be deleted when Wifi turn off, device restart or network settings reset.
     * Also when user manfully select to connect network will unblock that network.
     */
    private final MissingCounterTimerLockList<String> mUserTemporarilyDisabledList;

    /**
     * Framework keeps a mapping from configKey to the randomized MAC address so that
     * when a user forgets a network and thne adds it back, the same randomized MAC address
     * will get used.
     */
    private final Map<String, String> mRandomizedMacAddressMapping;

    /**
     * Store the network update listeners.
     */
    private final List<OnNetworkUpdateListener> mListeners;

    private final FrameworkFacade mFrameworkFacade;
    private final DeviceConfigFacade mDeviceConfigFacade;

    /**
     * Verbose logging flag. Toggled by developer options.
     */
    private boolean mVerboseLoggingEnabled = false;
    /**
     * Current logged in user ID.
     */
    private int mCurrentUserId = UserHandle.SYSTEM.getIdentifier();
    /**
     * Flag to indicate that the new user's store has not yet been read since user switch.
     * Initialize this flag to |true| to trigger a read on the first user unlock after
     * bootup.
     */
    private boolean mPendingUnlockStoreRead = true;
    /**
     * Flag to indicate if we have performed a read from store at all. This is used to gate
     * any user unlock/switch operations until we read the store (Will happen if wifi is disabled
     * when user updates from N to O).
     */
    private boolean mPendingStoreRead = true;
    /**
     * Flag to indicate if the user unlock was deferred until the store load occurs.
     */
    private boolean mDeferredUserUnlockRead = false;
    /**
     * This is keeping track of the next network ID to be assigned. Any new networks will be
     * assigned |mNextNetworkId| as network ID.
     */
    private int mNextNetworkId = 0;
    /**
     * This is used to remember which network was selected successfully last by an app. This is set
     * when an app invokes {@link #enableNetwork(int, boolean, int)} with |disableOthers| flag set.
     * This is the only way for an app to request connection to a specific network using the
     * {@link WifiManager} API's.
     */
    private int mLastSelectedNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
    private long mLastSelectedTimeStamp =
            WifiConfiguration.NetworkSelectionStatus.INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP;

    // Store data for network list and deleted ephemeral SSID list.  Used for serializing
    // parsing data to/from the config store.
    private final NetworkListSharedStoreData mNetworkListSharedStoreData;
    private final NetworkListUserStoreData mNetworkListUserStoreData;
    private final RandomizedMacStoreData mRandomizedMacStoreData;

    /**
     * Create new instance of WifiConfigManager.
     */
    WifiConfigManager(
            Context context, Clock clock, UserManager userManager,
            WifiCarrierInfoManager wifiCarrierInfoManager, WifiKeyStore wifiKeyStore,
            WifiConfigStore wifiConfigStore,
            WifiPermissionsUtil wifiPermissionsUtil,
            WifiPermissionsWrapper wifiPermissionsWrapper,
            WifiInjector wifiInjector,
            NetworkListSharedStoreData networkListSharedStoreData,
            NetworkListUserStoreData networkListUserStoreData,
            RandomizedMacStoreData randomizedMacStoreData,
            FrameworkFacade frameworkFacade, Handler handler,
            DeviceConfigFacade deviceConfigFacade, WifiScoreCard wifiScoreCard,
            LruConnectionTracker lruConnectionTracker) {
        mContext = context;
        mClock = clock;
        mUserManager = userManager;
        mBackupManagerProxy = new BackupManagerProxy();
        mWifiCarrierInfoManager = wifiCarrierInfoManager;
        mWifiKeyStore = wifiKeyStore;
        mWifiConfigStore = wifiConfigStore;
        mWifiPermissionsUtil = wifiPermissionsUtil;
        mWifiPermissionsWrapper = wifiPermissionsWrapper;
        mWifiInjector = wifiInjector;
        mWifiScoreCard = wifiScoreCard;

        mConfiguredNetworks = new ConfigurationMap(userManager);
        mScanDetailCaches = new HashMap<>(16, 0.75f);
        mUserTemporarilyDisabledList =
                new MissingCounterTimerLockList<>(SCAN_RESULT_MISSING_COUNT_THRESHOLD, mClock);
        mRandomizedMacAddressMapping = new HashMap<>();
        mListeners = new ArrayList<>();

        // Register store data for network list and deleted ephemeral SSIDs.
        mNetworkListSharedStoreData = networkListSharedStoreData;
        mNetworkListUserStoreData = networkListUserStoreData;
        mRandomizedMacStoreData = randomizedMacStoreData;
        mWifiConfigStore.registerStoreData(mNetworkListSharedStoreData);
        mWifiConfigStore.registerStoreData(mNetworkListUserStoreData);
        mWifiConfigStore.registerStoreData(mRandomizedMacStoreData);

        mFrameworkFacade = frameworkFacade;
        mDeviceConfigFacade = deviceConfigFacade;

        mLocalLog = new LocalLog(
                context.getSystemService(ActivityManager.class).isLowRamDevice() ? 128 : 256);
        mMacAddressUtil = mWifiInjector.getMacAddressUtil();
        mLruConnectionTracker = lruConnectionTracker;
    }

    /**
     * Network Selection disable reason thresholds. These numbers are used to debounce network
     * failures before we disable them.
     *
     * @param reason int reason code
     * @return the disable threshold, or -1 if not found.
     */
    @VisibleForTesting
    public static int getNetworkSelectionDisableThreshold(
            @NetworkSelectionDisableReason int reason) {
        DisableReasonInfo info = DISABLE_REASON_INFOS.get(reason);
        if (info == null) {
            Log.e(TAG, "Unrecognized network disable reason code for disable threshold: " + reason);
            return -1;
        } else {
            return info.mDisableThreshold;
        }
    }

    /**
     * Network Selection disable timeout for each kind of error. After the timeout in milliseconds,
     * enable the network again.
     */
    @VisibleForTesting
    public static int getNetworkSelectionDisableTimeoutMillis(
            @NetworkSelectionDisableReason int reason) {
        DisableReasonInfo info = DISABLE_REASON_INFOS.get(reason);
        if (info == null) {
            Log.e(TAG, "Unrecognized network disable reason code for disable timeout: " + reason);
            return -1;
        } else {
            return info.mDisableTimeoutMillis;
        }
    }

    /**
     * Determine if the framework should perform "aggressive" MAC randomization when connecting
     * to the SSID or FQDN in the input WifiConfiguration.
     * @param config
     * @return
     */
    public boolean shouldUseAggressiveRandomization(WifiConfiguration config) {
        if (!isMacRandomizationSupported()
                || config.macRandomizationSetting != WifiConfiguration.RANDOMIZATION_PERSISTENT) {
            return false;
        }
        if (mFrameworkFacade.getIntegerSetting(mContext,
                ENHANCED_MAC_RANDOMIZATION_FEATURE_FORCE_ENABLE_FLAG, 0) == 1) {
            return true;
        }
        if (config.getIpConfiguration().getIpAssignment() == IpConfiguration.IpAssignment.STATIC) {
            return false;
        }
        if (config.isPasspoint()) {
            return isNetworkOptInForAggressiveRandomization(config.FQDN);
        } else {
            return isNetworkOptInForAggressiveRandomization(config.SSID);
        }
    }

    private boolean isNetworkOptInForAggressiveRandomization(String ssidOrFqdn) {
        Set<String> perDeviceSsidBlocklist = new ArraySet<>(mContext.getResources().getStringArray(
                R.array.config_wifi_aggressive_randomization_ssid_blocklist));
        if (mDeviceConfigFacade.getAggressiveMacRandomizationSsidBlocklist().contains(ssidOrFqdn)
                || perDeviceSsidBlocklist.contains(ssidOrFqdn)) {
            return false;
        }
        Set<String> perDeviceSsidAllowlist = new ArraySet<>(mContext.getResources().getStringArray(
                R.array.config_wifi_aggressive_randomization_ssid_allowlist));
        return mDeviceConfigFacade.getAggressiveMacRandomizationSsidAllowlist().contains(ssidOrFqdn)
                || perDeviceSsidAllowlist.contains(ssidOrFqdn);
    }

    @VisibleForTesting
    protected int getRandomizedMacAddressMappingSize() {
        return mRandomizedMacAddressMapping.size();
    }

    /**
     * The persistent randomized MAC address is locally generated for each SSID and does not
     * change until factory reset of the device. In the initial Q release the per-SSID randomized
     * MAC is saved on the device, but in an update the storing of randomized MAC is removed.
     * Instead, the randomized MAC is calculated directly from the SSID and a on device secret.
     * For backward compatibility, this method first checks the device storage for saved
     * randomized MAC. If it is not found or the saved MAC is invalid then it will calculate the
     * randomized MAC directly.
     *
     * In the future as devices launched on Q no longer get supported, this method should get
     * simplified to return the calculated MAC address directly.
     * @param config the WifiConfiguration to obtain MAC address for.
     * @return persistent MAC address for this WifiConfiguration
     */
    private MacAddress getPersistentMacAddress(WifiConfiguration config) {
        // mRandomizedMacAddressMapping had been the location to save randomized MAC addresses.
        String persistentMacString = mRandomizedMacAddressMapping.get(
                config.getKey());
        // Use the MAC address stored in the storage if it exists and is valid. Otherwise
        // use the MAC address calculated from a hash function as the persistent MAC.
        if (persistentMacString != null) {
            try {
                return MacAddress.fromString(persistentMacString);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Error creating randomized MAC address from stored value.");
                mRandomizedMacAddressMapping.remove(config.getKey());
            }
        }
        MacAddress result = mMacAddressUtil.calculatePersistentMac(config.getKey(),
                mMacAddressUtil.obtainMacRandHashFunction(Process.WIFI_UID));
        if (result == null) {
            result = mMacAddressUtil.calculatePersistentMac(config.getKey(),
                    mMacAddressUtil.obtainMacRandHashFunction(Process.WIFI_UID));
        }
        if (result == null) {
            Log.wtf(TAG, "Failed to generate MAC address from KeyStore even after retrying. "
                    + "Using locally generated MAC address instead.");
            result = config.getRandomizedMacAddress();
            if (DEFAULT_MAC_ADDRESS.equals(result)) {
                result = MacAddressUtils.createRandomUnicastAddress();
            }
        }
        return result;
    }

    /**
     * Sets the randomized MAC expiration time based on the DHCP lease duration.
     * This should be called every time DHCP lease information is obtained.
     */
    public void updateRandomizedMacExpireTime(WifiConfiguration config, long dhcpLeaseSeconds) {
        WifiConfiguration internalConfig = getInternalConfiguredNetwork(config.networkId);
        if (internalConfig == null) {
            return;
        }
        long expireDurationMs = (dhcpLeaseSeconds & 0xffffffffL) * 1000;
        expireDurationMs = Math.max(AGGRESSIVE_MAC_REFRESH_MS_MIN, expireDurationMs);
        expireDurationMs = Math.min(AGGRESSIVE_MAC_REFRESH_MS_MAX, expireDurationMs);
        internalConfig.randomizedMacExpirationTimeMs = mClock.getWallClockMillis()
                + expireDurationMs;
    }

    /**
     * Obtain the persistent MAC address by first reading from an internal database. If non exists
     * then calculate the persistent MAC using HMAC-SHA256.
     * Finally set the randomized MAC of the configuration to the randomized MAC obtained.
     * @param config the WifiConfiguration to make the update
     * @return the persistent MacAddress or null if the operation is unsuccessful
     */
    private MacAddress setRandomizedMacToPersistentMac(WifiConfiguration config) {
        MacAddress persistentMac = getPersistentMacAddress(config);
        if (persistentMac == null || persistentMac.equals(config.getRandomizedMacAddress())) {
            return persistentMac;
        }
        WifiConfiguration internalConfig = getInternalConfiguredNetwork(config.networkId);
        internalConfig.setRandomizedMacAddress(persistentMac);
        return persistentMac;
    }

    /**
     * This method is called before connecting to a network that has "aggressive randomization"
     * enabled, and will re-randomize the MAC address if needed.
     * @param config the WifiConfiguration to make the update
     * @return the updated MacAddress
     */
    private MacAddress updateRandomizedMacIfNeeded(WifiConfiguration config) {
        boolean shouldUpdateMac = config.randomizedMacExpirationTimeMs
                < mClock.getWallClockMillis();
        if (!shouldUpdateMac) {
            return config.getRandomizedMacAddress();
        }
        WifiConfiguration internalConfig = getInternalConfiguredNetwork(config.networkId);
        internalConfig.setRandomizedMacAddress(MacAddressUtils.createRandomUnicastAddress());
        return internalConfig.getRandomizedMacAddress();
    }

    /**
     * Returns the randomized MAC address that should be used for this WifiConfiguration.
     * This API may return a randomized MAC different from the persistent randomized MAC if
     * the WifiConfiguration is configured for aggressive MAC randomization.
     * @param config
     * @return MacAddress
     */
    public MacAddress getRandomizedMacAndUpdateIfNeeded(WifiConfiguration config) {
        MacAddress mac = shouldUseAggressiveRandomization(config)
                ? updateRandomizedMacIfNeeded(config)
                : setRandomizedMacToPersistentMac(config);
        return mac;
    }

    /**
     * Enable/disable verbose logging in WifiConfigManager & its helper classes.
     */
    public void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            mVerboseLoggingEnabled = true;
        } else {
            mVerboseLoggingEnabled = false;
        }
        mWifiConfigStore.enableVerboseLogging(mVerboseLoggingEnabled);
        mWifiKeyStore.enableVerboseLogging(mVerboseLoggingEnabled);
    }

    /**
     * Helper method to mask all passwords/keys from the provided WifiConfiguration object. This
     * is needed when the network configurations are being requested via the public WifiManager
     * API's.
     * This currently masks the following elements: psk, wepKeys & enterprise config password.
     */
    private void maskPasswordsInWifiConfiguration(WifiConfiguration configuration) {
        if (!TextUtils.isEmpty(configuration.preSharedKey)) {
            configuration.preSharedKey = PASSWORD_MASK;
        }
        if (configuration.wepKeys != null) {
            for (int i = 0; i < configuration.wepKeys.length; i++) {
                if (!TextUtils.isEmpty(configuration.wepKeys[i])) {
                    configuration.wepKeys[i] = PASSWORD_MASK;
                }
            }
        }
        if (configuration.enterpriseConfig != null && !TextUtils.isEmpty(
                configuration.enterpriseConfig.getPassword())) {
            configuration.enterpriseConfig.setPassword(PASSWORD_MASK);
        }
    }

    /**
     * Helper method to mask randomized MAC address from the provided WifiConfiguration Object.
     * This is needed when the network configurations are being requested via the public
     * WifiManager API's. This method puts "02:00:00:00:00:00" as the MAC address.
     * @param configuration WifiConfiguration to hide the MAC address
     */
    private void maskRandomizedMacAddressInWifiConfiguration(WifiConfiguration configuration) {
        configuration.setRandomizedMacAddress(DEFAULT_MAC_ADDRESS);
    }

    /**
     * Helper method to create a copy of the provided internal WifiConfiguration object to be
     * passed to external modules.
     *
     * @param configuration provided WifiConfiguration object.
     * @param maskPasswords Mask passwords or not.
     * @param targetUid Target UID for MAC address reading: -1 = mask all, 0 = mask none, >0 =
     *                  mask all but the targetUid (carrier app).
     * @return Copy of the WifiConfiguration object.
     */
    private WifiConfiguration createExternalWifiConfiguration(
            WifiConfiguration configuration, boolean maskPasswords, int targetUid) {
        WifiConfiguration network = new WifiConfiguration(configuration);
        if (maskPasswords) {
            maskPasswordsInWifiConfiguration(network);
        }
        if (targetUid != Process.WIFI_UID && targetUid != Process.SYSTEM_UID
                && targetUid != configuration.creatorUid) {
            maskRandomizedMacAddressInWifiConfiguration(network);
        }
        if (!isMacRandomizationSupported()) {
            network.macRandomizationSetting = WifiConfiguration.RANDOMIZATION_NONE;
        }
        return network;
    }

    /**
     * Returns whether MAC randomization is supported on this device.
     * @param config
     * @return
     */
    private boolean isMacRandomizationSupported() {
        return mContext.getResources().getBoolean(
                R.bool.config_wifi_connected_mac_randomization_supported);
    }

    /**
     * Fetch the list of currently configured networks maintained in WifiConfigManager.
     *
     * This retrieves a copy of the internal configurations maintained by WifiConfigManager and
     * should be used for any public interfaces.
     *
     * @param savedOnly     Retrieve only saved networks.
     * @param maskPasswords Mask passwords or not.
     * @param targetUid Target UID for MAC address reading: -1 (Invalid UID) = mask all,
     *                  WIFI||SYSTEM = mask none, <other> = mask all but the targetUid (carrier
     *                  app).
     * @return List of WifiConfiguration objects representing the networks.
     */
    private List<WifiConfiguration> getConfiguredNetworks(
            boolean savedOnly, boolean maskPasswords, int targetUid) {
        List<WifiConfiguration> networks = new ArrayList<>();
        for (WifiConfiguration config : getInternalConfiguredNetworks()) {
            if (savedOnly && (config.ephemeral || config.isPasspoint())) {
                continue;
            }
            networks.add(createExternalWifiConfiguration(config, maskPasswords, targetUid));
        }
        return networks;
    }

    /**
     * Retrieves the list of all configured networks with passwords masked.
     *
     * @return List of WifiConfiguration objects representing the networks.
     */
    public List<WifiConfiguration> getConfiguredNetworks() {
        return getConfiguredNetworks(false, true, Process.WIFI_UID);
    }

    /**
     * Retrieves the list of all configured networks with the passwords in plaintext.
     *
     * WARNING: Don't use this to pass network configurations to external apps. Should only be
     * sent to system apps/wifi stack, when there is a need for passwords in plaintext.
     * TODO: Need to understand the current use case of this API.
     *
     * @return List of WifiConfiguration objects representing the networks.
     */
    public List<WifiConfiguration> getConfiguredNetworksWithPasswords() {
        return getConfiguredNetworks(false, false, Process.WIFI_UID);
    }

    /**
     * Retrieves the list of all configured networks with the passwords masked.
     *
     * @return List of WifiConfiguration objects representing the networks.
     */
    public List<WifiConfiguration> getSavedNetworks(int targetUid) {
        return getConfiguredNetworks(true, true, targetUid);
    }

    /**
     * Retrieves the configured network corresponding to the provided networkId with password
     * masked.
     *
     * @param networkId networkId of the requested network.
     * @return WifiConfiguration object if found, null otherwise.
     */
    public WifiConfiguration getConfiguredNetwork(int networkId) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return null;
        }
        // Create a new configuration object with the passwords masked to send out to the external
        // world.
        return createExternalWifiConfiguration(config, true, Process.WIFI_UID);
    }

    /**
     * Retrieves the configured network corresponding to the provided config key with password
     * masked.
     *
     * @param configKey configKey of the requested network.
     * @return WifiConfiguration object if found, null otherwise.
     */
    public WifiConfiguration getConfiguredNetwork(String configKey) {
        WifiConfiguration config = getInternalConfiguredNetwork(configKey);
        if (config == null) {
            return null;
        }
        // Create a new configuration object with the passwords masked to send out to the external
        // world.
        return createExternalWifiConfiguration(config, true, Process.WIFI_UID);
    }

    /**
     * Retrieves the configured network corresponding to the provided networkId with password
     * in plaintext.
     *
     * WARNING: Don't use this to pass network configurations to external apps. Should only be
     * sent to system apps/wifi stack, when there is a need for passwords in plaintext.
     *
     * @param networkId networkId of the requested network.
     * @return WifiConfiguration object if found, null otherwise.
     */
    public WifiConfiguration getConfiguredNetworkWithPassword(int networkId) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return null;
        }
        // Create a new configuration object without the passwords masked to send out to the
        // external world.
        return createExternalWifiConfiguration(config, false, Process.WIFI_UID);
    }

    /**
     * Retrieves the configured network corresponding to the provided networkId
     * without any masking.
     *
     * WARNING: Don't use this to pass network configurations except in the wifi stack, when
     * there is a need for passwords and randomized MAC address.
     *
     * @param networkId networkId of the requested network.
     * @return Copy of WifiConfiguration object if found, null otherwise.
     */
    public WifiConfiguration getConfiguredNetworkWithoutMasking(int networkId) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return null;
        }
        return new WifiConfiguration(config);
    }

    /**
     * Helper method to retrieve all the internal WifiConfiguration objects corresponding to all
     * the networks in our database.
     */
    private Collection<WifiConfiguration> getInternalConfiguredNetworks() {
        return mConfiguredNetworks.valuesForCurrentUser();
    }

    /**
     * Helper method to retrieve the internal WifiConfiguration object corresponding to the
     * provided configuration in our database.
     * This first attempts to find the network using the provided network ID in configuration,
     * else it attempts to find a matching configuration using the configKey.
     */
    private WifiConfiguration getInternalConfiguredNetwork(WifiConfiguration config) {
        WifiConfiguration internalConfig = mConfiguredNetworks.getForCurrentUser(config.networkId);
        if (internalConfig != null) {
            return internalConfig;
        }
        internalConfig = mConfiguredNetworks.getByConfigKeyForCurrentUser(config.getKey());
        if (internalConfig == null) {
            Log.e(TAG, "Cannot find network with networkId " + config.networkId
                    + " or configKey " + config.getKey());
        }
        return internalConfig;
    }

    /**
     * Helper method to retrieve the internal WifiConfiguration object corresponding to the
     * provided network ID in our database.
     */
    private WifiConfiguration getInternalConfiguredNetwork(int networkId) {
        if (networkId == WifiConfiguration.INVALID_NETWORK_ID) {
            return null;
        }
        WifiConfiguration internalConfig = mConfiguredNetworks.getForCurrentUser(networkId);
        if (internalConfig == null) {
            Log.e(TAG, "Cannot find network with networkId " + networkId);
        }
        return internalConfig;
    }

    /**
     * Helper method to retrieve the internal WifiConfiguration object corresponding to the
     * provided configKey in our database.
     */
    private WifiConfiguration getInternalConfiguredNetwork(String configKey) {
        WifiConfiguration internalConfig =
                mConfiguredNetworks.getByConfigKeyForCurrentUser(configKey);
        if (internalConfig == null) {
            Log.e(TAG, "Cannot find network with configKey " + configKey);
        }
        return internalConfig;
    }

    /**
     * Method to send out the configured networks change broadcast when network configurations
     * changed.
     *
     * In Android R we stopped sending out WifiConfiguration due to user privacy concerns.
     * Thus, no matter how many networks changed,
     * {@link WifiManager#EXTRA_MULTIPLE_NETWORKS_CHANGED} is always set to true, and
     * {@link WifiManager#EXTRA_WIFI_CONFIGURATION} is always null.
     *
     * @param reason  The reason for the change, should be one of WifiManager.CHANGE_REASON_ADDED,
     *                WifiManager.CHANGE_REASON_REMOVED, or WifiManager.CHANGE_REASON_CHANGE.
     */
    private void sendConfiguredNetworkChangedBroadcast(int reason) {
        Intent intent = new Intent(WifiManager.CONFIGURED_NETWORKS_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_MULTIPLE_NETWORKS_CHANGED, true);
        intent.putExtra(WifiManager.EXTRA_CHANGE_REASON, reason);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL, Manifest.permission.ACCESS_WIFI_STATE);
    }

    /**
     * Checks if |uid| has permission to modify the provided configuration.
     *
     * @param config         WifiConfiguration object corresponding to the network to be modified.
     * @param uid            UID of the app requesting the modification.
     * @param packageName    Package name of the app requesting the modification.
     */
    private boolean canModifyNetwork(WifiConfiguration config, int uid,
            @Nullable String packageName) {
        // System internals can always update networks; they're typically only
        // making meteredHint or meteredOverride changes
        if (uid == Process.SYSTEM_UID) {
            return true;
        }

        // Passpoint configurations are generated and managed by PasspointManager. They can be
        // added by either PasspointNetworkNominator (for auto connection) or Settings app
        // (for manual connection), and need to be removed once the connection is completed.
        // Since it is "owned" by us, so always allow us to modify them.
        if (config.isPasspoint() && uid == Process.WIFI_UID) {
            return true;
        }

        // EAP-SIM/AKA/AKA' network needs framework to update the anonymous identity provided
        // by authenticator back to the WifiConfiguration object.
        // Since it is "owned" by us, so always allow us to modify them.
        if (config.enterpriseConfig != null
                && uid == Process.WIFI_UID
                && config.enterpriseConfig.isAuthenticationSimBased()) {
            return true;
        }

        final boolean isDeviceOwner = mWifiPermissionsUtil.isDeviceOwner(uid, packageName);

        // If |uid| corresponds to the device owner, allow all modifications.
        if (isDeviceOwner) {
            return true;
        }

        final boolean isCreator = (config.creatorUid == uid);

        // WiFi config lockdown related logic. At this point we know uid is NOT a Device Owner.
        final boolean isConfigEligibleForLockdown =
                mWifiPermissionsUtil.isDeviceOwner(config.creatorUid, config.creatorName);
        if (!isConfigEligibleForLockdown) {
            // App that created the network or settings app (i.e user) has permission to
            // modify the network.
            return isCreator || mWifiPermissionsUtil.checkNetworkSettingsPermission(uid);
        }

        final ContentResolver resolver = mContext.getContentResolver();
        final boolean isLockdownFeatureEnabled = Settings.Global.getInt(resolver,
                Settings.Global.WIFI_DEVICE_OWNER_CONFIGS_LOCKDOWN, 0) != 0;
        return !isLockdownFeatureEnabled
                // If not locked down, settings app (i.e user) has permission to modify the network.
                && mWifiPermissionsUtil.checkNetworkSettingsPermission(uid);
    }

    /**
     * Check if the given UID belongs to the current foreground user. This is
     * used to prevent apps running in background users from modifying network
     * configurations.
     * <p>
     * UIDs belonging to system internals (such as SystemUI) are always allowed,
     * since they always run as {@link UserHandle#USER_SYSTEM}.
     *
     * @param uid uid of the app.
     * @return true if the given UID belongs to the current foreground user,
     *         otherwise false.
     */
    private boolean doesUidBelongToCurrentUser(int uid) {
        if (uid == android.os.Process.SYSTEM_UID
                // UIDs with the NETWORK_SETTINGS permission are always allowed since they are
                // acting on behalf of the user.
                || mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
            return true;
        } else {
            UserHandle currentUser = UserHandle.of(mCurrentUserId);
            UserHandle callingUser = UserHandle.getUserHandleForUid(uid);
            return currentUser.equals(callingUser)
                    || mUserManager.isSameProfileGroup(currentUser, callingUser);
        }
    }

    /**
     * Copy over public elements from an external WifiConfiguration object to the internal
     * configuration object if element has been set in the provided external WifiConfiguration.
     * The only exception is the hidden |IpConfiguration| parameters, these need to be copied over
     * for every update.
     *
     * This method updates all elements that are common to both network addition & update.
     * The following fields of {@link WifiConfiguration} are not copied from external configs:
     *  > networkId - These are allocated by Wi-Fi stack internally for any new configurations.
     *  > status - The status needs to be explicitly updated using
     *             {@link WifiManager#enableNetwork(int, boolean)} or
     *             {@link WifiManager#disableNetwork(int)}.
     *
     * @param internalConfig WifiConfiguration object in our internal map.
     * @param externalConfig WifiConfiguration object provided from the external API.
     */
    private void mergeWithInternalWifiConfiguration(
            WifiConfiguration internalConfig, WifiConfiguration externalConfig) {
        if (externalConfig.SSID != null) {
            internalConfig.SSID = externalConfig.SSID;
        }
        if (externalConfig.BSSID != null) {
            internalConfig.BSSID = externalConfig.BSSID.toLowerCase();
        }
        internalConfig.hiddenSSID = externalConfig.hiddenSSID;
        internalConfig.requirePmf = externalConfig.requirePmf;

        if (externalConfig.preSharedKey != null
                && !externalConfig.preSharedKey.equals(PASSWORD_MASK)) {
            internalConfig.preSharedKey = externalConfig.preSharedKey;
        }
        // Modify only wep keys are present in the provided configuration. This is a little tricky
        // because there is no easy way to tell if the app is actually trying to null out the
        // existing keys or not.
        if (externalConfig.wepKeys != null) {
            boolean hasWepKey = false;
            for (int i = 0; i < internalConfig.wepKeys.length; i++) {
                if (externalConfig.wepKeys[i] != null
                        && !externalConfig.wepKeys[i].equals(PASSWORD_MASK)) {
                    internalConfig.wepKeys[i] = externalConfig.wepKeys[i];
                    hasWepKey = true;
                }
            }
            if (hasWepKey) {
                internalConfig.wepTxKeyIndex = externalConfig.wepTxKeyIndex;
            }
        }
        if (externalConfig.FQDN != null) {
            internalConfig.FQDN = externalConfig.FQDN;
        }
        if (externalConfig.providerFriendlyName != null) {
            internalConfig.providerFriendlyName = externalConfig.providerFriendlyName;
        }
        if (externalConfig.roamingConsortiumIds != null) {
            internalConfig.roamingConsortiumIds = externalConfig.roamingConsortiumIds.clone();
        }

        // Copy over all the auth/protocol/key mgmt parameters if set.
        if (externalConfig.allowedAuthAlgorithms != null
                && !externalConfig.allowedAuthAlgorithms.isEmpty()) {
            internalConfig.allowedAuthAlgorithms =
                    (BitSet) externalConfig.allowedAuthAlgorithms.clone();
        }
        if (externalConfig.allowedProtocols != null
                && !externalConfig.allowedProtocols.isEmpty()) {
            internalConfig.allowedProtocols = (BitSet) externalConfig.allowedProtocols.clone();
        }
        if (externalConfig.allowedKeyManagement != null
                && !externalConfig.allowedKeyManagement.isEmpty()) {
            internalConfig.allowedKeyManagement =
                    (BitSet) externalConfig.allowedKeyManagement.clone();
        }
        if (externalConfig.allowedPairwiseCiphers != null
                && !externalConfig.allowedPairwiseCiphers.isEmpty()) {
            internalConfig.allowedPairwiseCiphers =
                    (BitSet) externalConfig.allowedPairwiseCiphers.clone();
        }
        if (externalConfig.allowedGroupCiphers != null
                && !externalConfig.allowedGroupCiphers.isEmpty()) {
            internalConfig.allowedGroupCiphers =
                    (BitSet) externalConfig.allowedGroupCiphers.clone();
        }
        if (externalConfig.allowedGroupManagementCiphers != null
                && !externalConfig.allowedGroupManagementCiphers.isEmpty()) {
            internalConfig.allowedGroupManagementCiphers =
                    (BitSet) externalConfig.allowedGroupManagementCiphers.clone();
        }
        // allowedSuiteBCiphers is set internally according to the certificate type

        // Copy over the |IpConfiguration| parameters if set.
        if (externalConfig.getIpConfiguration() != null) {
            IpConfiguration.IpAssignment ipAssignment = externalConfig.getIpAssignment();
            if (ipAssignment != IpConfiguration.IpAssignment.UNASSIGNED) {
                internalConfig.setIpAssignment(ipAssignment);
                if (ipAssignment == IpConfiguration.IpAssignment.STATIC) {
                    internalConfig.setStaticIpConfiguration(
                            new StaticIpConfiguration(externalConfig.getStaticIpConfiguration()));
                }
            }
            IpConfiguration.ProxySettings proxySettings = externalConfig.getProxySettings();
            if (proxySettings != IpConfiguration.ProxySettings.UNASSIGNED) {
                internalConfig.setProxySettings(proxySettings);
                if (proxySettings == IpConfiguration.ProxySettings.PAC
                        || proxySettings == IpConfiguration.ProxySettings.STATIC) {
                    internalConfig.setHttpProxy(new ProxyInfo(externalConfig.getHttpProxy()));
                }
            }
        }

        // Copy over the |WifiEnterpriseConfig| parameters if set.
        if (externalConfig.enterpriseConfig != null) {
            internalConfig.enterpriseConfig.copyFromExternal(
                    externalConfig.enterpriseConfig, PASSWORD_MASK);
        }

        // Copy over any metered information.
        internalConfig.meteredHint = externalConfig.meteredHint;
        internalConfig.meteredOverride = externalConfig.meteredOverride;

        // Copy trusted bit
        internalConfig.trusted = externalConfig.trusted;

        // Copy over macRandomizationSetting
        internalConfig.macRandomizationSetting = externalConfig.macRandomizationSetting;
        internalConfig.carrierId = externalConfig.carrierId;
        internalConfig.isHomeProviderNetwork = externalConfig.isHomeProviderNetwork;
    }

    /**
     * Set all the exposed defaults in the newly created WifiConfiguration object.
     * These fields have a default value advertised in our public documentation. The only exception
     * is the hidden |IpConfiguration| parameters, these have a default value even though they're
     * hidden.
     *
     * @param configuration provided WifiConfiguration object.
     */
    private void setDefaultsInWifiConfiguration(WifiConfiguration configuration) {
        configuration.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
        configuration.allowedProtocols.set(WifiConfiguration.Protocol.WPA);

        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);

        configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.GCMP_256);
        configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
        configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);

        configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.GCMP_256);
        configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
        configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
        configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
        configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104);

        configuration.allowedGroupManagementCiphers
                .set(WifiConfiguration.GroupMgmtCipher.BIP_GMAC_256);

        configuration.setIpAssignment(IpConfiguration.IpAssignment.DHCP);
        configuration.setProxySettings(IpConfiguration.ProxySettings.NONE);

        configuration.status = WifiConfiguration.Status.DISABLED;
        configuration.getNetworkSelectionStatus().setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_PERMANENTLY_DISABLED);
        configuration.getNetworkSelectionStatus().setNetworkSelectionDisableReason(
                NetworkSelectionStatus.DISABLED_BY_WIFI_MANAGER);
    }

    /**
     * Create a new internal WifiConfiguration object by copying over parameters from the provided
     * external configuration and set defaults for the appropriate parameters.
     *
     * @param externalConfig WifiConfiguration object provided from the external API.
     * @return New WifiConfiguration object with parameters merged from the provided external
     * configuration.
     */
    private WifiConfiguration createNewInternalWifiConfigurationFromExternal(
            WifiConfiguration externalConfig, int uid, @Nullable String packageName) {
        WifiConfiguration newInternalConfig = new WifiConfiguration();

        // First allocate a new network ID for the configuration.
        newInternalConfig.networkId = mNextNetworkId++;

        // First set defaults in the new configuration created.
        setDefaultsInWifiConfiguration(newInternalConfig);

        // Copy over all the public elements from the provided configuration.
        mergeWithInternalWifiConfiguration(newInternalConfig, externalConfig);

        // Copy over the hidden configuration parameters. These are the only parameters used by
        // system apps to indicate some property about the network being added.
        // These are only copied over for network additions and ignored for network updates.
        newInternalConfig.requirePmf = externalConfig.requirePmf;
        newInternalConfig.noInternetAccessExpected = externalConfig.noInternetAccessExpected;
        newInternalConfig.ephemeral = externalConfig.ephemeral;
        newInternalConfig.osu = externalConfig.osu;
        newInternalConfig.trusted = externalConfig.trusted;
        newInternalConfig.fromWifiNetworkSuggestion = externalConfig.fromWifiNetworkSuggestion;
        newInternalConfig.fromWifiNetworkSpecifier = externalConfig.fromWifiNetworkSpecifier;
        newInternalConfig.useExternalScores = externalConfig.useExternalScores;
        newInternalConfig.shared = externalConfig.shared;
        newInternalConfig.updateIdentifier = externalConfig.updateIdentifier;
        newInternalConfig.setPasspointUniqueId(externalConfig.getPasspointUniqueId());

        // Add debug information for network addition.
        newInternalConfig.creatorUid = newInternalConfig.lastUpdateUid = uid;
        newInternalConfig.creatorName = newInternalConfig.lastUpdateName =
                packageName != null ? packageName : mContext.getPackageManager().getNameForUid(uid);
        initRandomizedMacForInternalConfig(newInternalConfig);
        return newInternalConfig;
    }

    /**
     * Create a new internal WifiConfiguration object by copying over parameters from the provided
     * external configuration to a copy of the existing internal WifiConfiguration object.
     *
     * @param internalConfig WifiConfiguration object in our internal map.
     * @param externalConfig WifiConfiguration object provided from the external API.
     * @return Copy of existing WifiConfiguration object with parameters merged from the provided
     * configuration.
     */
    private WifiConfiguration updateExistingInternalWifiConfigurationFromExternal(
            WifiConfiguration internalConfig, WifiConfiguration externalConfig, int uid,
            @Nullable String packageName) {
        WifiConfiguration newInternalConfig = new WifiConfiguration(internalConfig);

        // Copy over all the public elements from the provided configuration.
        mergeWithInternalWifiConfiguration(newInternalConfig, externalConfig);

        // Add debug information for network update.
        newInternalConfig.lastUpdateUid = uid;
        newInternalConfig.lastUpdateName =
                packageName != null ? packageName : mContext.getPackageManager().getNameForUid(uid);

        return newInternalConfig;
    }

    private void logUserActionEvents(WifiConfiguration before, WifiConfiguration after) {
        // Logs changes in meteredOverride.
        if (before.meteredOverride != after.meteredOverride) {
            mWifiInjector.getWifiMetrics().logUserActionEvent(
                    WifiMetrics.convertMeteredOverrideEnumToUserActionEventType(
                            after.meteredOverride),
                    after.networkId);
        }

        // Logs changes in macRandomizationSetting.
        if (before.macRandomizationSetting != after.macRandomizationSetting) {
            mWifiInjector.getWifiMetrics().logUserActionEvent(
                    after.macRandomizationSetting == WifiConfiguration.RANDOMIZATION_NONE
                            ? UserActionEvent.EVENT_CONFIGURE_MAC_RANDOMIZATION_OFF
                            : UserActionEvent.EVENT_CONFIGURE_MAC_RANDOMIZATION_ON,
                    after.networkId);
        }
    }

    /**
     * Add a network or update a network configuration to our database.
     * If the supplied networkId is INVALID_NETWORK_ID, we create a new empty
     * network configuration. Otherwise, the networkId should refer to an existing configuration.
     *
     * @param config provided WifiConfiguration object.
     * @param uid UID of the app requesting the network addition/modification.
     * @param packageName Package name of the app requesting the network addition/modification.
     * @return NetworkUpdateResult object representing status of the update.
     */
    private NetworkUpdateResult addOrUpdateNetworkInternal(WifiConfiguration config, int uid,
                                                           @Nullable String packageName) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Adding/Updating network " + config.getPrintableSsid());
        }
        WifiConfiguration newInternalConfig = null;

        // First check if we already have a network with the provided network id or configKey.
        WifiConfiguration existingInternalConfig = getInternalConfiguredNetwork(config);
        // No existing network found. So, potentially a network add.
        if (existingInternalConfig == null) {
            if (!WifiConfigurationUtil.validate(config, WifiConfigurationUtil.VALIDATE_FOR_ADD)) {
                Log.e(TAG, "Cannot add network with invalid config");
                return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
            }
            newInternalConfig =
                    createNewInternalWifiConfigurationFromExternal(config, uid, packageName);
            // Since the original config provided may have had an empty
            // {@link WifiConfiguration#allowedKeyMgmt} field, check again if we already have a
            // network with the the same configkey.
            existingInternalConfig = getInternalConfiguredNetwork(newInternalConfig.getKey());
        }
        // Existing network found. So, a network update.
        if (existingInternalConfig != null) {
            if (!WifiConfigurationUtil.validate(
                    config, WifiConfigurationUtil.VALIDATE_FOR_UPDATE)) {
                Log.e(TAG, "Cannot update network with invalid config");
                return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
            }
            // Check for the app's permission before we let it update this network.
            if (!canModifyNetwork(existingInternalConfig, uid, packageName)) {
                Log.e(TAG, "UID " + uid + " does not have permission to update configuration "
                        + config.getKey());
                return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
            }
            if (mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                    && !config.isPasspoint()) {
                logUserActionEvents(existingInternalConfig, config);
            }
            newInternalConfig =
                    updateExistingInternalWifiConfigurationFromExternal(
                            existingInternalConfig, config, uid, packageName);
        }

        // Only add networks with proxy settings if the user has permission to
        if (WifiConfigurationUtil.hasProxyChanged(existingInternalConfig, newInternalConfig)
                && !canModifyProxySettings(uid, packageName)) {
            Log.e(TAG, "UID " + uid + " does not have permission to modify proxy Settings "
                    + config.getKey() + ". Must have NETWORK_SETTINGS,"
                    + " or be device or profile owner.");
            return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
        }

        if (WifiConfigurationUtil.hasMacRandomizationSettingsChanged(existingInternalConfig,
                newInternalConfig) && !mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)
                && !mWifiPermissionsUtil.checkNetworkSetupWizardPermission(uid)
                && !(newInternalConfig.isPasspoint() && uid == newInternalConfig.creatorUid)) {
            Log.e(TAG, "UID " + uid + " does not have permission to modify MAC randomization "
                    + "Settings " + config.getKey() + ". Must have "
                    + "NETWORK_SETTINGS or NETWORK_SETUP_WIZARD or be the creator adding or "
                    + "updating a passpoint network.");
            return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
        }

        // Update the keys for saved enterprise networks. For Passpoint, the certificates
        // and keys are installed at the time the provider is installed. For suggestion enterprise
        // network the certificates and keys are installed at the time the suggestion is added
        if (!config.isPasspoint() && !config.fromWifiNetworkSuggestion && config.isEnterprise()) {
            if (!(mWifiKeyStore.updateNetworkKeys(newInternalConfig, existingInternalConfig))) {
                return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
            }
        }

        boolean newNetwork = (existingInternalConfig == null);
        // This is needed to inform IpClient about any IP configuration changes.
        boolean hasIpChanged =
                newNetwork || WifiConfigurationUtil.hasIpChanged(
                        existingInternalConfig, newInternalConfig);
        boolean hasProxyChanged =
                newNetwork || WifiConfigurationUtil.hasProxyChanged(
                        existingInternalConfig, newInternalConfig);
        // Reset the |hasEverConnected| flag if the credential parameters changed in this update.
        boolean hasCredentialChanged =
                newNetwork || WifiConfigurationUtil.hasCredentialChanged(
                        existingInternalConfig, newInternalConfig);
        if (hasCredentialChanged) {
            newInternalConfig.getNetworkSelectionStatus().setHasEverConnected(false);
        }

        // Add it to our internal map. This will replace any existing network configuration for
        // updates.
        try {
            mConfiguredNetworks.put(newInternalConfig);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to add network to config map", e);
            return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
        }
        // Only re-enable network: 1. add or update user saved network; 2. add or update a user
        // saved passpoint network framework consider it is a new network.
        if (!newInternalConfig.fromWifiNetworkSuggestion
                && (!newInternalConfig.isPasspoint() || newNetwork)) {
            userEnabledNetwork(newInternalConfig.networkId);
        }

        // Stage the backup of the SettingsProvider package which backs this up.
        mBackupManagerProxy.notifyDataChanged();

        NetworkUpdateResult result =
                new NetworkUpdateResult(hasIpChanged, hasProxyChanged, hasCredentialChanged);
        result.setIsNewNetwork(newNetwork);
        result.setNetworkId(newInternalConfig.networkId);

        localLog("addOrUpdateNetworkInternal: added/updated config."
                + " netId=" + newInternalConfig.networkId
                + " configKey=" + newInternalConfig.getKey()
                + " uid=" + Integer.toString(newInternalConfig.creatorUid)
                + " name=" + newInternalConfig.creatorName);
        return result;
    }

    /**
     * Add a network or update a network configuration to our database.
     * If the supplied networkId is INVALID_NETWORK_ID, we create a new empty
     * network configuration. Otherwise, the networkId should refer to an existing configuration.
     *
     * @param config provided WifiConfiguration object.
     * @param uid UID of the app requesting the network addition/modification.
     * @param packageName Package name of the app requesting the network addition/modification.
     * @return NetworkUpdateResult object representing status of the update.
     */
    public NetworkUpdateResult addOrUpdateNetwork(WifiConfiguration config, int uid,
                                                  @Nullable String packageName) {
        if (!doesUidBelongToCurrentUser(uid)) {
            Log.e(TAG, "UID " + uid + " not visible to the current user");
            return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
        }
        if (config == null) {
            Log.e(TAG, "Cannot add/update network with null config");
            return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
        }
        if (mPendingStoreRead) {
            Log.e(TAG, "Cannot add/update network before store is read!");
            return new NetworkUpdateResult(WifiConfiguration.INVALID_NETWORK_ID);
        }
        WifiConfiguration existingConfig = getInternalConfiguredNetwork(config);
        if (!config.isEphemeral()) {
            // Removes the existing ephemeral network if it exists to add this configuration.
            if (existingConfig != null && existingConfig.isEphemeral()) {
                // In this case, new connection for this config won't happen because same
                // network is already registered as an ephemeral network.
                // Clear the Ephemeral Network to address the situation.
                removeNetwork(
                        existingConfig.networkId, existingConfig.creatorUid, config.creatorName);
            }
        }

        NetworkUpdateResult result = addOrUpdateNetworkInternal(config, uid, packageName);
        if (!result.isSuccess()) {
            Log.e(TAG, "Failed to add/update network " + config.getPrintableSsid());
            return result;
        }
        WifiConfiguration newConfig = getInternalConfiguredNetwork(result.getNetworkId());
        sendConfiguredNetworkChangedBroadcast(
                result.isNewNetwork()
                        ? WifiManager.CHANGE_REASON_ADDED
                        : WifiManager.CHANGE_REASON_CONFIG_CHANGE);
        // Unless the added network is ephemeral or Passpoint, persist the network update/addition.
        if (!config.ephemeral && !config.isPasspoint()) {
            saveToStore(true);
        }

        for (OnNetworkUpdateListener listener : mListeners) {
            if (result.isNewNetwork()) {
                listener.onNetworkAdded(
                        createExternalWifiConfiguration(newConfig, true, Process.WIFI_UID));
            } else {
                listener.onNetworkUpdated(
                        createExternalWifiConfiguration(newConfig, true, Process.WIFI_UID),
                        createExternalWifiConfiguration(existingConfig, true, Process.WIFI_UID));
            }
        }
        return result;
    }

    /**
     * Add a network or update a network configuration to our database.
     * If the supplied networkId is INVALID_NETWORK_ID, we create a new empty
     * network configuration. Otherwise, the networkId should refer to an existing configuration.
     *
     * @param config provided WifiConfiguration object.
     * @param uid    UID of the app requesting the network addition/modification.
     * @return NetworkUpdateResult object representing status of the update.
     */
    public NetworkUpdateResult addOrUpdateNetwork(WifiConfiguration config, int uid) {
        return addOrUpdateNetwork(config, uid, null);
    }

    /**
     * Removes the specified network configuration from our database.
     *
     * @param config provided WifiConfiguration object.
     * @param uid UID of the app requesting the network deletion.
     * @return true if successful, false otherwise.
     */
    private boolean removeNetworkInternal(WifiConfiguration config, int uid) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Removing network " + config.getPrintableSsid());
        }
        // Remove any associated enterprise keys for saved enterprise networks. Passpoint network
        // will remove the enterprise keys when provider is uninstalled. Suggestion enterprise
        // networks will remove the enterprise keys when suggestion is removed.
        if (!config.fromWifiNetworkSuggestion && !config.isPasspoint() && config.isEnterprise()) {
            mWifiKeyStore.removeKeys(config.enterpriseConfig);
        }

        removeConnectChoiceFromAllNetworks(config.getKey());
        mConfiguredNetworks.remove(config.networkId);
        mScanDetailCaches.remove(config.networkId);
        // Stage the backup of the SettingsProvider package which backs this up.
        mBackupManagerProxy.notifyDataChanged();
        mWifiInjector.getBssidBlocklistMonitor().handleNetworkRemoved(config.SSID);

        localLog("removeNetworkInternal: removed config."
                + " netId=" + config.networkId
                + " configKey=" + config.getKey()
                + " uid=" + Integer.toString(uid)
                + " name=" + mContext.getPackageManager().getNameForUid(uid));
        return true;
    }

    /**
     * Removes the specified network configuration from our database.
     *
     * @param networkId network ID of the provided network.
     * @param uid       UID of the app requesting the network deletion.
     * @return true if successful, false otherwise.
     */
    public boolean removeNetwork(int networkId, int uid, String packageName) {
        if (!doesUidBelongToCurrentUser(uid)) {
            Log.e(TAG, "UID " + uid + " not visible to the current user");
            return false;
        }
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return false;
        }
        if (!canModifyNetwork(config, uid, packageName)) {
            Log.e(TAG, "UID " + uid + " does not have permission to delete configuration "
                    + config.getKey());
            return false;
        }
        if (!removeNetworkInternal(config, uid)) {
            Log.e(TAG, "Failed to remove network " + config.getPrintableSsid());
            return false;
        }
        if (networkId == mLastSelectedNetworkId) {
            clearLastSelectedNetwork();
        }
        if (!config.ephemeral && !config.isPasspoint()) {
            mLruConnectionTracker.removeNetwork(config);
        }
        sendConfiguredNetworkChangedBroadcast(WifiManager.CHANGE_REASON_REMOVED);
        // Unless the removed network is ephemeral or Passpoint, persist the network removal.
        if (!config.ephemeral && !config.isPasspoint()) {
            saveToStore(true);
        }
        for (OnNetworkUpdateListener listener : mListeners) {
            listener.onNetworkRemoved(
                    createExternalWifiConfiguration(config, true, Process.WIFI_UID));
        }
        return true;
    }

    private String getCreatorPackageName(WifiConfiguration config) {
        String creatorName = config.creatorName;
        // getNameForUid (Stored in WifiConfiguration.creatorName) returns a concatenation of name
        // and uid for shared UIDs ("name:uid").
        if (!creatorName.contains(":")) {
            return creatorName; // regular app not using shared UID.
        }
        // Separate the package name from the string for app using shared UID.
        return creatorName.substring(0, creatorName.indexOf(":"));
    }

    /**
     * Remove all networks associated with an application.
     *
     * @param app Application info of the package of networks to remove.
     * @return the {@link Set} of networks that were removed by this call. Networks which matched
     *         but failed to remove are omitted from this set.
     */
    public Set<Integer> removeNetworksForApp(ApplicationInfo app) {
        if (app == null || app.packageName == null) {
            return Collections.<Integer>emptySet();
        }
        Log.d(TAG, "Remove all networks for app " + app);
        Set<Integer> removedNetworks = new ArraySet<>();
        WifiConfiguration[] copiedConfigs =
                mConfiguredNetworks.valuesForAllUsers().toArray(new WifiConfiguration[0]);
        for (WifiConfiguration config : copiedConfigs) {
            if (app.uid != config.creatorUid
                    || !app.packageName.equals(getCreatorPackageName(config))) {
                continue;
            }
            localLog("Removing network " + config.SSID
                    + ", application \"" + app.packageName + "\" uninstalled"
                    + " from user " + UserHandle.getUserHandleForUid(app.uid));
            if (removeNetwork(config.networkId, config.creatorUid, config.creatorName)) {
                removedNetworks.add(config.networkId);
            }
        }
        return removedNetworks;
    }

    /**
     * Remove all networks associated with a user.
     *
     * @param userId The identifier of the user which is being removed.
     * @return the {@link Set} of networks that were removed by this call. Networks which matched
     *         but failed to remove are omitted from this set.
     */
    Set<Integer> removeNetworksForUser(int userId) {
        Log.d(TAG, "Remove all networks for user " + userId);
        Set<Integer> removedNetworks = new ArraySet<>();
        WifiConfiguration[] copiedConfigs =
                mConfiguredNetworks.valuesForAllUsers().toArray(new WifiConfiguration[0]);
        for (WifiConfiguration config : copiedConfigs) {
            if (userId != UserHandle.getUserHandleForUid(config.creatorUid).getIdentifier()) {
                continue;
            }
            localLog("Removing network " + config.SSID + ", user " + userId + " removed");
            if (removeNetwork(config.networkId, config.creatorUid, config.creatorName)) {
                removedNetworks.add(config.networkId);
            }
        }
        return removedNetworks;
    }

    /**
     * Iterates through the internal list of configured networks and removes any ephemeral or
     * passpoint network configurations which are transient in nature.
     *
     * @return true if a network was removed, false otherwise.
     */
    public boolean removeAllEphemeralOrPasspointConfiguredNetworks() {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Removing all passpoint or ephemeral configured networks");
        }
        boolean didRemove = false;
        WifiConfiguration[] copiedConfigs =
                mConfiguredNetworks.valuesForAllUsers().toArray(new WifiConfiguration[0]);
        for (WifiConfiguration config : copiedConfigs) {
            if (config.isPasspoint()) {
                Log.d(TAG, "Removing passpoint network config " + config.getKey());
                removeNetwork(config.networkId, config.creatorUid, config.creatorName);
                didRemove = true;
            } else if (config.ephemeral) {
                Log.d(TAG, "Removing ephemeral network config " + config.getKey());
                removeNetwork(config.networkId, config.creatorUid, config.creatorName);
                didRemove = true;
            }
        }
        return didRemove;
    }

    /**
     * Removes the suggestion network configuration matched with {@code configKey} provided.
     *
     * @param configKey Config Key for the corresponding network suggestion.
     * @return true if a network was removed, false otherwise.
     */
    public boolean removeSuggestionConfiguredNetwork(@NonNull String configKey) {
        WifiConfiguration config = getInternalConfiguredNetwork(configKey);
        if (config != null && config.ephemeral && config.fromWifiNetworkSuggestion) {
            Log.d(TAG, "Removing suggestion network config " + config.getKey());
            return removeNetwork(config.networkId, config.creatorUid, config.creatorName);
        }
        return false;
    }

    /**
     * Removes the passpoint network configuration matched with {@code configKey} provided.
     *
     * @param configKey Config Key for the corresponding passpoint.
     * @return true if a network was removed, false otherwise.
     */
    public boolean removePasspointConfiguredNetwork(@NonNull String configKey) {
        WifiConfiguration config = getInternalConfiguredNetwork(configKey);
        if (config != null && config.isPasspoint()) {
            Log.d(TAG, "Removing passpoint network config " + config.getKey());
            return removeNetwork(config.networkId, config.creatorUid, config.creatorName);
        }
        return false;
    }

    /**
     * Check whether a network belong to a known list of networks that may not support randomized
     * MAC.
     * @param networkId
     * @return true if the network is in the hotlist and MAC randomization is enabled.
     */
    public boolean isInFlakyRandomizationSsidHotlist(int networkId) {
        WifiConfiguration config = getConfiguredNetwork(networkId);
        return config != null
                && config.macRandomizationSetting == WifiConfiguration.RANDOMIZATION_PERSISTENT
                && mDeviceConfigFacade.getRandomizationFlakySsidHotlist().contains(config.SSID);
    }

    /**
     * Helper method to mark a network enabled for network selection.
     */
    private void setNetworkSelectionEnabled(WifiConfiguration config) {
        NetworkSelectionStatus status = config.getNetworkSelectionStatus();
        if (status.getNetworkSelectionStatus()
                != NetworkSelectionStatus.NETWORK_SELECTION_ENABLED) {
            localLog("setNetworkSelectionEnabled: configKey=" + config.getKey()
                    + " old networkStatus=" + status.getNetworkStatusString()
                    + " disableReason=" + status.getNetworkSelectionDisableReasonString());
        }
        status.setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_ENABLED);
        status.setDisableTime(
                NetworkSelectionStatus.INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP);
        status.setNetworkSelectionDisableReason(NetworkSelectionStatus.DISABLED_NONE);

        // Clear out all the disable reason counters.
        status.clearDisableReasonCounter();
        for (OnNetworkUpdateListener listener : mListeners) {
            listener.onNetworkEnabled(
                    createExternalWifiConfiguration(config, true, Process.WIFI_UID));
        }
    }

    /**
     * Helper method to mark a network temporarily disabled for network selection.
     */
    private void setNetworkSelectionTemporarilyDisabled(
            WifiConfiguration config, int disableReason) {
        NetworkSelectionStatus status = config.getNetworkSelectionStatus();
        status.setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_TEMPORARY_DISABLED);
        // Only need a valid time filled in for temporarily disabled networks.
        status.setDisableTime(mClock.getElapsedSinceBootMillis());
        status.setNetworkSelectionDisableReason(disableReason);
        for (OnNetworkUpdateListener listener : mListeners) {
            listener.onNetworkTemporarilyDisabled(
                    createExternalWifiConfiguration(config, true, Process.WIFI_UID), disableReason);
        }
    }

    /**
     * Helper method to mark a network permanently disabled for network selection.
     */
    private void setNetworkSelectionPermanentlyDisabled(
            WifiConfiguration config, int disableReason) {
        NetworkSelectionStatus status = config.getNetworkSelectionStatus();
        status.setNetworkSelectionStatus(
                NetworkSelectionStatus.NETWORK_SELECTION_PERMANENTLY_DISABLED);
        status.setDisableTime(
                NetworkSelectionStatus.INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP);
        status.setNetworkSelectionDisableReason(disableReason);
        for (OnNetworkUpdateListener listener : mListeners) {
            WifiConfiguration configForListener = new WifiConfiguration(config);
            listener.onNetworkPermanentlyDisabled(
                    createExternalWifiConfiguration(config, true, Process.WIFI_UID), disableReason);
        }
    }

    /**
     * Helper method to set the publicly exposed status for the network and send out the network
     * status change broadcast.
     */
    private void setNetworkStatus(WifiConfiguration config, int status) {
        config.status = status;
        sendConfiguredNetworkChangedBroadcast(WifiManager.CHANGE_REASON_CONFIG_CHANGE);
    }

    /**
     * Sets a network's status (both internal and public) according to the update reason and
     * its current state.
     *
     * This updates the network's {@link WifiConfiguration#mNetworkSelectionStatus} field and the
     * public {@link WifiConfiguration#status} field if the network is either enabled or
     * permanently disabled.
     *
     * @param config network to be updated.
     * @param reason reason code for update.
     * @return true if the input configuration has been updated, false otherwise.
     */
    private boolean setNetworkSelectionStatus(WifiConfiguration config, int reason) {
        NetworkSelectionStatus networkStatus = config.getNetworkSelectionStatus();
        if (reason < 0 || reason >= NetworkSelectionStatus.NETWORK_SELECTION_DISABLED_MAX) {
            Log.e(TAG, "Invalid Network disable reason " + reason);
            return false;
        }
        if (reason == NetworkSelectionStatus.DISABLED_NONE) {
            setNetworkSelectionEnabled(config);
            setNetworkStatus(config, WifiConfiguration.Status.ENABLED);
        } else if (reason < NetworkSelectionStatus.PERMANENTLY_DISABLED_STARTING_INDEX) {
            setNetworkSelectionTemporarilyDisabled(config, reason);
        } else {
            setNetworkSelectionPermanentlyDisabled(config, reason);
            setNetworkStatus(config, WifiConfiguration.Status.DISABLED);
        }
        localLog("setNetworkSelectionStatus: configKey=" + config.getKey()
                + " networkStatus=" + networkStatus.getNetworkStatusString() + " disableReason="
                + networkStatus.getNetworkSelectionDisableReasonString());
        saveToStore(false);
        return true;
    }

    /**
     * Update a network's status (both internal and public) according to the update reason and
     * its current state.
     *
     * @param config network to be updated.
     * @param reason reason code for update.
     * @return true if the input configuration has been updated, false otherwise.
     */
    private boolean updateNetworkSelectionStatus(WifiConfiguration config, int reason) {
        NetworkSelectionStatus networkStatus = config.getNetworkSelectionStatus();
        if (reason != NetworkSelectionStatus.DISABLED_NONE) {

            // Do not update SSID blacklist with information if this is the only
            // SSID be observed. By ignoring it we will cause additional failures
            // which will trigger Watchdog.
            if (reason == NetworkSelectionStatus.DISABLED_ASSOCIATION_REJECTION
                    || reason == NetworkSelectionStatus.DISABLED_AUTHENTICATION_FAILURE
                    || reason == NetworkSelectionStatus.DISABLED_DHCP_FAILURE) {
                if (mWifiInjector.getWifiLastResortWatchdog().shouldIgnoreSsidUpdate()) {
                    if (mVerboseLoggingEnabled) {
                        Log.v(TAG, "Ignore update network selection status "
                                    + "since Watchdog trigger is activated");
                    }
                    return false;
                }
            }

            networkStatus.incrementDisableReasonCounter(reason);
            // For network disable reasons, we should only update the status if we cross the
            // threshold.
            int disableReasonCounter = networkStatus.getDisableReasonCounter(reason);
            int disableReasonThreshold = getNetworkSelectionDisableThreshold(reason);
            if (disableReasonCounter < disableReasonThreshold) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Disable counter for network " + config.getPrintableSsid()
                            + " for reason "
                            + NetworkSelectionStatus.getNetworkSelectionDisableReasonString(reason)
                            + " is " + networkStatus.getDisableReasonCounter(reason)
                            + " and threshold is " + disableReasonThreshold);
                }
                return true;
            }
        }
        return setNetworkSelectionStatus(config, reason);
    }

    /**
     * Update a network's status (both internal and public) according to the update reason and
     * its current state.
     *
     * Each network has 2 status:
     * 1. NetworkSelectionStatus: This is internal selection status of the network. This is used
     * for temporarily disabling a network for Network Selector.
     * 2. Status: This is the exposed status for a network. This is mostly set by
     * the public API's {@link WifiManager#enableNetwork(int, boolean)} &
     * {@link WifiManager#disableNetwork(int)}.
     *
     * @param networkId network ID of the network that needs the update.
     * @param reason    reason to update the network.
     * @return true if the input configuration has been updated, false otherwise.
     */
    public boolean updateNetworkSelectionStatus(int networkId, int reason) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return false;
        }
        return updateNetworkSelectionStatus(config, reason);
    }

    /**
     * Attempt to re-enable a network for network selection, if this network was either:
     * a) Previously temporarily disabled, but its disable timeout has expired, or
     * b) Previously disabled because of a user switch, but is now visible to the current
     * user.
     *
     * @param config configuration for the network to be re-enabled for network selection. The
     *               network corresponding to the config must be visible to the current user.
     * @return true if the network identified by {@param config} was re-enabled for qualified
     * network selection, false otherwise.
     */
    private boolean tryEnableNetwork(WifiConfiguration config) {
        NetworkSelectionStatus networkStatus = config.getNetworkSelectionStatus();
        if (networkStatus.isNetworkTemporaryDisabled()) {
            long timeDifferenceMs =
                    mClock.getElapsedSinceBootMillis() - networkStatus.getDisableTime();
            int disableReason = networkStatus.getNetworkSelectionDisableReason();
            int blockedBssids = Math.min(MAX_BLOCKED_BSSID_PER_NETWORK,
                    mWifiInjector.getBssidBlocklistMonitor()
                            .getNumBlockedBssidsForSsid(config.SSID));
            // if no BSSIDs are blocked then we should keep trying to connect to something
            long disableTimeoutMs = 0;
            if (blockedBssids > 0) {
                double multiplier = Math.pow(2.0, blockedBssids - 1.0);
                disableTimeoutMs = (long) (getNetworkSelectionDisableTimeoutMillis(disableReason)
                        * multiplier);
            }
            if (timeDifferenceMs >= disableTimeoutMs) {
                return updateNetworkSelectionStatus(
                        config, NetworkSelectionStatus.DISABLED_NONE);
            }
        }
        return false;
    }

    /**
     * Attempt to re-enable a network for network selection, if this network was either:
     * a) Previously temporarily disabled, but its disable timeout has expired, or
     * b) Previously disabled because of a user switch, but is now visible to the current
     * user.
     *
     * @param networkId the id of the network to be checked for possible unblock (due to timeout)
     * @return true if the network identified by {@param networkId} was re-enabled for qualified
     * network selection, false otherwise.
     */
    public boolean tryEnableNetwork(int networkId) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return false;
        }
        return tryEnableNetwork(config);
    }

    /**
     * Enable a network using the public {@link WifiManager#enableNetwork(int, boolean)} API.
     *
     * @param networkId     network ID of the network that needs the update.
     * @param disableOthers Whether to disable all other networks or not. This is used to indicate
     *                      that the app requested connection to a specific network.
     * @param uid           uid of the app requesting the update.
     * @return true if it succeeds, false otherwise
     */
    public boolean enableNetwork(int networkId, boolean disableOthers, int uid,
                                 String packageName) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Enabling network " + networkId + " (disableOthers " + disableOthers + ")");
        }
        if (!doesUidBelongToCurrentUser(uid)) {
            Log.e(TAG, "UID " + uid + " not visible to the current user");
            return false;
        }
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return false;
        }
        // Set the "last selected" flag even if the app does not have permissions to modify this
        // network config. Apps are allowed to connect to networks even if they don't have
        // permission to modify it.
        if (disableOthers) {
            setLastSelectedNetwork(networkId);
        }
        if (!canModifyNetwork(config, uid, packageName)) {
            Log.e(TAG, "UID " + uid + " does not have permission to update configuration "
                    + config.getKey());
            return false;
        }
        if (!updateNetworkSelectionStatus(
                networkId, WifiConfiguration.NetworkSelectionStatus.DISABLED_NONE)) {
            return false;
        }
        saveToStore(true);
        return true;
    }

    /**
     * Disable a network using the public {@link WifiManager#disableNetwork(int)} API.
     *
     * @param networkId network ID of the network that needs the update.
     * @param uid       uid of the app requesting the update.
     * @return true if it succeeds, false otherwise
     */
    public boolean disableNetwork(int networkId, int uid, String packageName) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Disabling network " + networkId);
        }
        if (!doesUidBelongToCurrentUser(uid)) {
            Log.e(TAG, "UID " + uid + " not visible to the current user");
            return false;
        }
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return false;
        }
        // Reset the "last selected" flag even if the app does not have permissions to modify this
        // network config.
        if (networkId == mLastSelectedNetworkId) {
            clearLastSelectedNetwork();
        }
        if (!canModifyNetwork(config, uid, packageName)) {
            Log.e(TAG, "UID " + uid + " does not have permission to update configuration "
                    + config.getKey());
            return false;
        }
        if (!updateNetworkSelectionStatus(
                networkId, NetworkSelectionStatus.DISABLED_BY_WIFI_MANAGER)) {
            return false;
        }
        saveToStore(true);
        return true;
    }

    /**
     * Changes the user's choice to allow auto-join using the
     * {@link WifiManager#allowAutojoin(int, boolean)} API.
     *
     * @param networkId network ID of the network that needs the update.
     * @param choice the choice to allow auto-join or not
     * @return true if it succeeds, false otherwise
     */
    public boolean allowAutojoin(int networkId, boolean choice) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Setting allowAutojoin to " + choice + " for netId " + networkId);
        }
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            Log.e(TAG, "allowAutojoin: Supplied networkId " + networkId
                    + " has no matching config");
            return false;
        }

        config.allowAutojoin = choice;
        if (!choice) {
            removeConnectChoiceFromAllNetworks(config.getKey());
            clearNetworkConnectChoice(config.networkId);
        }
        sendConfiguredNetworkChangedBroadcast(WifiManager.CHANGE_REASON_CONFIG_CHANGE);
        if (!config.ephemeral) {
            saveToStore(true);
        }
        return true;
    }

    /**
     * Updates the last connected UID for the provided configuration.
     *
     * @param networkId network ID corresponding to the network.
     * @param uid       uid of the app requesting the connection.
     * @return true if the network was found, false otherwise.
     */
    public boolean updateLastConnectUid(int networkId, int uid) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Update network last connect UID for " + networkId);
        }
        if (!doesUidBelongToCurrentUser(uid)) {
            Log.e(TAG, "UID " + uid + " not visible to the current user");
            return false;
        }
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return false;
        }
        config.lastConnectUid = uid;
        return true;
    }

    /**
     * Updates a network configuration after a successful connection to it.
     *
     * This method updates the following WifiConfiguration elements:
     * 1. Set the |lastConnected| timestamp.
     * 2. Increment |numAssociation| counter.
     * 3. Clear the disable reason counters in the associated |NetworkSelectionStatus|.
     * 4. Set the hasEverConnected| flag in the associated |NetworkSelectionStatus|.
     * 5. Sets the status of network as |CURRENT|.
     *
     * @param networkId network ID corresponding to the network.
     * @return true if the network was found, false otherwise.
     */
    public boolean updateNetworkAfterConnect(int networkId) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Update network after connect for " + networkId);
        }
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return false;
        }

        // Only record connection order for non-passpoint from user saved or suggestion.
        if (!config.isPasspoint() && (config.fromWifiNetworkSuggestion || !config.ephemeral)) {
            mLruConnectionTracker.addNetwork(config);
        }
        config.lastConnected = mClock.getWallClockMillis();
        config.numAssociation++;
        config.getNetworkSelectionStatus().clearDisableReasonCounter();
        config.getNetworkSelectionStatus().setHasEverConnected(true);
        setNetworkStatus(config, WifiConfiguration.Status.CURRENT);
        saveToStore(false);
        return true;
    }

    /**
     * Updates a network configuration after disconnection from it.
     *
     * This method updates the following WifiConfiguration elements:
     * 1. Set the |lastDisConnected| timestamp.
     * 2. Sets the status of network back to |ENABLED|.
     *
     * @param networkId network ID corresponding to the network.
     * @return true if the network was found, false otherwise.
     */
    public boolean updateNetworkAfterDisconnect(int networkId) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Update network after disconnect for " + networkId);
        }
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return false;
        }
        config.lastDisconnected = mClock.getWallClockMillis();
        config.randomizedMacExpirationTimeMs = Math.max(config.randomizedMacExpirationTimeMs,
                config.lastDisconnected + AGGRESSIVE_MAC_WAIT_AFTER_DISCONNECT_MS);
        // If the network hasn't been disabled, mark it back as
        // enabled after disconnection.
        if (config.status == WifiConfiguration.Status.CURRENT) {
            setNetworkStatus(config, WifiConfiguration.Status.ENABLED);
        }
        saveToStore(false);
        return true;
    }

    /**
     * Set default GW MAC address for the provided network.
     *
     * @param networkId network ID corresponding to the network.
     * @param macAddress MAC address of the gateway to be set.
     * @return true if the network was found, false otherwise.
     */
    public boolean setNetworkDefaultGwMacAddress(int networkId, String macAddress) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return false;
        }
        config.defaultGwMacAddress = macAddress;
        return true;
    }

    /**
     * Clear the {@link NetworkSelectionStatus#mCandidate},
     * {@link NetworkSelectionStatus#mCandidateScore} &
     * {@link NetworkSelectionStatus#mSeenInLastQualifiedNetworkSelection} for the provided network.
     *
     * This is invoked by Network Selector at the start of every selection procedure to clear all
     * configured networks' scan-result-candidates.
     *
     * @param networkId network ID corresponding to the network.
     * @return true if the network was found, false otherwise.
     */
    public boolean clearNetworkCandidateScanResult(int networkId) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Clear network candidate scan result for " + networkId);
        }
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return false;
        }
        config.getNetworkSelectionStatus().setCandidate(null);
        config.getNetworkSelectionStatus().setCandidateScore(Integer.MIN_VALUE);
        config.getNetworkSelectionStatus().setSeenInLastQualifiedNetworkSelection(false);
        return true;
    }

    /**
     * Set the {@link NetworkSelectionStatus#mCandidate},
     * {@link NetworkSelectionStatus#mCandidateScore} &
     * {@link NetworkSelectionStatus#mSeenInLastQualifiedNetworkSelection} for the provided network.
     *
     * This is invoked by Network Selector when it sees a network during network selection procedure
     * to set the scan result candidate.
     *
     * @param networkId  network ID corresponding to the network.
     * @param scanResult Candidate ScanResult associated with this network.
     * @param score      Score assigned to the candidate.
     * @return true if the network was found, false otherwise.
     */
    public boolean setNetworkCandidateScanResult(int networkId, ScanResult scanResult, int score) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Set network candidate scan result " + scanResult + " for " + networkId);
        }
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            Log.e(TAG, "Cannot find network for " + networkId);
            return false;
        }
        config.getNetworkSelectionStatus().setCandidate(scanResult);
        config.getNetworkSelectionStatus().setCandidateScore(score);
        config.getNetworkSelectionStatus().setSeenInLastQualifiedNetworkSelection(true);
        return true;
    }

    /**
     * Iterate through all the saved networks and remove the provided configuration from the
     * {@link NetworkSelectionStatus#mConnectChoice} from them.
     *
     * This is invoked when a network is removed from our records.
     *
     * @param connectChoiceConfigKey ConfigKey corresponding to the network that is being removed.
     */
    private void removeConnectChoiceFromAllNetworks(String connectChoiceConfigKey) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Removing connect choice from all networks " + connectChoiceConfigKey);
        }
        if (connectChoiceConfigKey == null) {
            return;
        }
        for (WifiConfiguration config : getInternalConfiguredNetworks()) {
            WifiConfiguration.NetworkSelectionStatus status = config.getNetworkSelectionStatus();
            String connectChoice = status.getConnectChoice();
            if (TextUtils.equals(connectChoice, connectChoiceConfigKey)) {
                Log.d(TAG, "remove connect choice:" + connectChoice + " from " + config.SSID
                        + " : " + config.networkId);
                clearNetworkConnectChoice(config.networkId);
            }
        }
    }

    /**
     * Clear the {@link NetworkSelectionStatus#mConnectChoice} for the provided network.
     *
     * @param networkId network ID corresponding to the network.
     * @return true if the network was found, false otherwise.
     */
    public boolean clearNetworkConnectChoice(int networkId) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Clear network connect choice for " + networkId);
        }
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return false;
        }
        config.getNetworkSelectionStatus().setConnectChoice(null);
        saveToStore(false);
        return true;
    }

    /**
     * Set the {@link NetworkSelectionStatus#mConnectChoice} for the provided network.
     *
     * This is invoked by Network Selector when the user overrides the currently connected network
     * choice.
     *
     * @param networkId              network ID corresponding to the network.
     * @param connectChoiceConfigKey ConfigKey corresponding to the network which was chosen over
     *                               this network.
     * @param timestamp              timestamp at which the choice was made.
     * @return true if the network was found, false otherwise.
     */
    public boolean setNetworkConnectChoice(
            int networkId, String connectChoiceConfigKey) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Set network connect choice " + connectChoiceConfigKey + " for " + networkId);
        }
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return false;
        }
        config.getNetworkSelectionStatus().setConnectChoice(connectChoiceConfigKey);
        saveToStore(false);
        return true;
    }

    /**
     * Increments the number of no internet access reports in the provided network.
     *
     * @param networkId network ID corresponding to the network.
     * @return true if the network was found, false otherwise.
     */
    public boolean incrementNetworkNoInternetAccessReports(int networkId) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return false;
        }
        config.numNoInternetAccessReports++;
        return true;
    }

    /**
     * Sets the internet access is validated or not in the provided network.
     *
     * @param networkId network ID corresponding to the network.
     * @param validated Whether access is validated or not.
     * @return true if the network was found, false otherwise.
     */
    public boolean setNetworkValidatedInternetAccess(int networkId, boolean validated) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return false;
        }
        config.validatedInternetAccess = validated;
        config.numNoInternetAccessReports = 0;
        saveToStore(false);
        return true;
    }

    /**
     * Sets whether the internet access is expected or not in the provided network.
     *
     * @param networkId network ID corresponding to the network.
     * @param expected  Whether access is expected or not.
     * @return true if the network was found, false otherwise.
     */
    public boolean setNetworkNoInternetAccessExpected(int networkId, boolean expected) {
        WifiConfiguration config = getInternalConfiguredNetwork(networkId);
        if (config == null) {
            return false;
        }
        config.noInternetAccessExpected = expected;
        return true;
    }

    /**
     * Helper method to clear out the {@link #mNextNetworkId} user/app network selection. This
     * is done when either the corresponding network is either removed or disabled.
     */
    public void clearLastSelectedNetwork() {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Clearing last selected network");
        }
        mLastSelectedNetworkId = WifiConfiguration.INVALID_NETWORK_ID;
        mLastSelectedTimeStamp = NetworkSelectionStatus.INVALID_NETWORK_SELECTION_DISABLE_TIMESTAMP;
    }

    /**
     * Helper method to mark a network as the last selected one by an app/user. This is set
     * when an app invokes {@link #enableNetwork(int, boolean, int)} with |disableOthers| flag set.
     * This is used by network selector to assign a special bonus during network selection.
     */
    private void setLastSelectedNetwork(int networkId) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Setting last selected network to " + networkId);
        }
        mLastSelectedNetworkId = networkId;
        mLastSelectedTimeStamp = mClock.getElapsedSinceBootMillis();
    }

    /**
     * Retrieve the network Id corresponding to the last network that was explicitly selected by
     * an app/user.
     *
     * @return network Id corresponding to the last selected network.
     */
    public int getLastSelectedNetwork() {
        return mLastSelectedNetworkId;
    }

    /**
     * Retrieve the configKey corresponding to the last network that was explicitly selected by
     * an app/user.
     *
     * @return network Id corresponding to the last selected network.
     */
    public String getLastSelectedNetworkConfigKey() {
        if (mLastSelectedNetworkId == WifiConfiguration.INVALID_NETWORK_ID) {
            return "";
        }
        WifiConfiguration config = getInternalConfiguredNetwork(mLastSelectedNetworkId);
        if (config == null) {
            return "";
        }
        return config.getKey();
    }

    /**
     * Retrieve the time stamp at which a network was explicitly selected by an app/user.
     *
     * @return timestamp in milliseconds from boot when this was set.
     */
    public long getLastSelectedTimeStamp() {
        return mLastSelectedTimeStamp;
    }

    /**
     * Helper method to get the scan detail cache entry {@link #mScanDetailCaches} for the provided
     * network.
     *
     * @param networkId network ID corresponding to the network.
     * @return existing {@link ScanDetailCache} entry if one exists or null.
     */
    public ScanDetailCache getScanDetailCacheForNetwork(int networkId) {
        return mScanDetailCaches.get(networkId);
    }

    /**
     * Helper method to get or create a scan detail cache entry {@link #mScanDetailCaches} for
     * the provided network.
     *
     * @param config configuration corresponding to the the network.
     * @return existing {@link ScanDetailCache} entry if one exists or a new instance created for
     * this network.
     */
    private ScanDetailCache getOrCreateScanDetailCacheForNetwork(WifiConfiguration config) {
        if (config == null) return null;
        ScanDetailCache cache = getScanDetailCacheForNetwork(config.networkId);
        if (cache == null && config.networkId != WifiConfiguration.INVALID_NETWORK_ID) {
            cache = new ScanDetailCache(
                    config, SCAN_CACHE_ENTRIES_MAX_SIZE, SCAN_CACHE_ENTRIES_TRIM_SIZE);
            mScanDetailCaches.put(config.networkId, cache);
        }
        return cache;
    }

    /**
     * Saves the provided ScanDetail into the corresponding scan detail cache entry
     * {@link #mScanDetailCaches} for the provided network.
     *
     * @param config     configuration corresponding to the the network.
     * @param scanDetail new scan detail instance to be saved into the cache.
     */
    private void saveToScanDetailCacheForNetwork(
            WifiConfiguration config, ScanDetail scanDetail) {
        ScanResult scanResult = scanDetail.getScanResult();

        WifiScoreCard.PerNetwork network = mWifiScoreCard.lookupNetwork(config.SSID);
        network.addFrequency(scanResult.frequency);
        ScanDetailCache scanDetailCache = getOrCreateScanDetailCacheForNetwork(config);
        if (scanDetailCache == null) {
            Log.e(TAG, "Could not allocate scan cache for " + config.getPrintableSsid());
            return;
        }

        // Adding a new BSSID
        if (config.ephemeral) {
            // For an ephemeral Wi-Fi config, the ScanResult should be considered
            // untrusted.
            scanResult.untrusted = true;
        }

        // Add the scan detail to this network's scan detail cache.
        scanDetailCache.put(scanDetail);

        // Since we added a scan result to this configuration, re-attempt linking.
        // TODO: Do we really need to do this after every scan result?
        attemptNetworkLinking(config);
    }

    /**
     * Retrieves a configured network corresponding to the provided scan detail if one exists.
     *
     * @param scanDetail ScanDetail instance  to use for looking up the network.
     * @return WifiConfiguration object representing the network corresponding to the scanDetail,
     * null if none exists.
     *
     * TODO (b/142035508): This should only return saved networks (and rename to
     * getSavedNetworkForScanDetail()).
     */
    public WifiConfiguration getConfiguredNetworkForScanDetail(ScanDetail scanDetail) {
        ScanResult scanResult = scanDetail.getScanResult();
        if (scanResult == null) {
            Log.e(TAG, "No scan result found in scan detail");
            return null;
        }
        WifiConfiguration config = null;
        try {
            config = mConfiguredNetworks.getByScanResultForCurrentUser(scanResult);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Failed to lookup network from config map", e);
        }
        if (config != null) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "getSavedNetworkFromScanDetail Found " + config.getKey()
                        + " for " + scanResult.SSID + "[" + scanResult.capabilities + "]");
            }
        }
        return config;
    }

    /**
     * Caches the provided |scanDetail| into the corresponding scan detail cache entry
     * {@link #mScanDetailCaches} for the retrieved network.
     *
     * @param scanDetail input a scanDetail from the scan result
     * TODO (b/142035508): This should only return saved networks (and rename to
     * updateScanDetailCacheFromScanDetail()).
     */
    public void updateScanDetailCacheFromScanDetail(ScanDetail scanDetail) {
        WifiConfiguration network = getConfiguredNetworkForScanDetail(scanDetail);
        if (network == null) {
            return;
        }
        saveToScanDetailCacheForNetwork(network, scanDetail);
    }
    /**
     * Retrieves a configured network corresponding to the provided scan detail if one exists and
     * caches the provided |scanDetail| into the corresponding scan detail cache entry
     * {@link #mScanDetailCaches} for the retrieved network.
     *
     * @param scanDetail input a scanDetail from the scan result
     * @return WifiConfiguration object representing the network corresponding to the scanDetail,
     * null if none exists.
     * TODO (b/142035508): This should only return saved networks (and rename to
     * getSavedNetworkForScanDetailAndCache()).
     */
    public WifiConfiguration getConfiguredNetworkForScanDetailAndCache(ScanDetail scanDetail) {
        WifiConfiguration network = getConfiguredNetworkForScanDetail(scanDetail);
        if (network == null) {
            return null;
        }
        saveToScanDetailCacheForNetwork(network, scanDetail);
        // Cache DTIM values parsed from the beacon frame Traffic Indication Map (TIM)
        // Information Element (IE), into the associated WifiConfigurations. Most of the
        // time there is no TIM IE in the scan result (Probe Response instead of Beacon
        // Frame), these scanResult DTIM's are negative and ignored.
        // Used for metrics collection.
        if (scanDetail.getNetworkDetail() != null
                && scanDetail.getNetworkDetail().getDtimInterval() > 0) {
            network.dtimInterval = scanDetail.getNetworkDetail().getDtimInterval();
        }
        return createExternalWifiConfiguration(network, true, Process.WIFI_UID);
    }

    /**
     * Update the scan detail cache associated with current connected network with latest
     * RSSI value in the provided WifiInfo.
     * This is invoked when we get an RSSI poll update after connection.
     *
     * @param info WifiInfo instance pointing to the current connected network.
     */
    public void updateScanDetailCacheFromWifiInfo(WifiInfo info) {
        WifiConfiguration config = getInternalConfiguredNetwork(info.getNetworkId());
        ScanDetailCache scanDetailCache = getScanDetailCacheForNetwork(info.getNetworkId());
        if (config != null && scanDetailCache != null) {
            ScanDetail scanDetail = scanDetailCache.getScanDetail(info.getBSSID());
            if (scanDetail != null) {
                ScanResult result = scanDetail.getScanResult();
                long previousSeen = result.seen;
                int previousRssi = result.level;
                // Update the scan result
                scanDetail.setSeen();
                result.level = info.getRssi();
                // Average the RSSI value
                long maxAge = SCAN_RESULT_MAXIMUM_AGE_MS;
                long age = result.seen - previousSeen;
                if (previousSeen > 0 && age > 0 && age < maxAge / 2) {
                    // Average the RSSI with previously seen instances of this scan result
                    double alpha = 0.5 - (double) age / (double) maxAge;
                    result.level = (int) ((double) result.level * (1 - alpha)
                                        + (double) previousRssi * alpha);
                }
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "Updating scan detail cache freq=" + result.frequency
                            + " BSSID=" + result.BSSID
                            + " RSSI=" + result.level
                            + " for " + config.getKey());
                }
            }
        }
    }

    /**
     * Save the ScanDetail to the ScanDetailCache of the given network.  This is used
     * by {@link PasspointNetworkNominator} for caching
     * ScanDetail for newly created {@link WifiConfiguration} for Passpoint network.
     *
     * @param networkId The ID of the network to save ScanDetail to
     * @param scanDetail The ScanDetail to cache
     */
    public void updateScanDetailForNetwork(int networkId, ScanDetail scanDetail) {
        WifiConfiguration network = getInternalConfiguredNetwork(networkId);
        if (network == null) {
            return;
        }
        saveToScanDetailCacheForNetwork(network, scanDetail);
    }

    /**
     * Helper method to check if the 2 provided networks can be linked or not.
     * Networks are considered for linking if:
     * 1. Share the same GW MAC address.
     * 2. Scan results for the networks have AP's with MAC address which differ only in the last
     * nibble.
     *
     * @param network1         WifiConfiguration corresponding to network 1.
     * @param network2         WifiConfiguration corresponding to network 2.
     * @param scanDetailCache1 ScanDetailCache entry for network 1.
     * @param scanDetailCache1 ScanDetailCache entry for network 2.
     * @return true if the networks should be linked, false if the networks should be unlinked.
     */
    private boolean shouldNetworksBeLinked(
            WifiConfiguration network1, WifiConfiguration network2,
            ScanDetailCache scanDetailCache1, ScanDetailCache scanDetailCache2) {
        // TODO (b/30706406): Link networks only with same passwords if the
        // |mOnlyLinkSameCredentialConfigurations| flag is set.
        if (mContext.getResources().getBoolean(
                R.bool.config_wifi_only_link_same_credential_configurations)) {
            if (!TextUtils.equals(network1.preSharedKey, network2.preSharedKey)) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "shouldNetworksBeLinked unlink due to password mismatch");
                }
                return false;
            }
        }
        if (network1.defaultGwMacAddress != null && network2.defaultGwMacAddress != null) {
            // If both default GW are known, link only if they are equal
            if (network1.defaultGwMacAddress.equals(network2.defaultGwMacAddress)) {
                if (mVerboseLoggingEnabled) {
                    Log.v(TAG, "shouldNetworksBeLinked link due to same gw " + network2.SSID
                            + " and " + network1.SSID + " GW " + network1.defaultGwMacAddress);
                }
                return true;
            }
        } else {
            // We do not know BOTH default gateways hence we will try to link
            // hoping that WifiConfigurations are indeed behind the same gateway.
            // once both WifiConfiguration have been tried and thus once both default gateways
            // are known we will revisit the choice of linking them.
            if (scanDetailCache1 != null && scanDetailCache2 != null) {
                for (String abssid : scanDetailCache1.keySet()) {
                    for (String bbssid : scanDetailCache2.keySet()) {
                        if (abssid.regionMatches(
                                true, 0, bbssid, 0, LINK_CONFIGURATION_BSSID_MATCH_LENGTH)) {
                            // If first 16 ASCII characters of BSSID matches,
                            // we assume this is a DBDC.
                            if (mVerboseLoggingEnabled) {
                                Log.v(TAG, "shouldNetworksBeLinked link due to DBDC BSSID match "
                                        + network2.SSID + " and " + network1.SSID
                                        + " bssida " + abssid + " bssidb " + bbssid);
                            }
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Helper methods to link 2 networks together.
     *
     * @param network1 WifiConfiguration corresponding to network 1.
     * @param network2 WifiConfiguration corresponding to network 2.
     */
    private void linkNetworks(WifiConfiguration network1, WifiConfiguration network2) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "linkNetworks will link " + network2.getKey()
                    + " and " + network1.getKey());
        }
        if (network2.linkedConfigurations == null) {
            network2.linkedConfigurations = new HashMap<>();
        }
        if (network1.linkedConfigurations == null) {
            network1.linkedConfigurations = new HashMap<>();
        }
        // TODO (b/30638473): This needs to become a set instead of map, but it will need
        // public interface changes and need some migration of existing store data.
        network2.linkedConfigurations.put(network1.getKey(), 1);
        network1.linkedConfigurations.put(network2.getKey(), 1);
    }

    /**
     * Helper methods to unlink 2 networks from each other.
     *
     * @param network1 WifiConfiguration corresponding to network 1.
     * @param network2 WifiConfiguration corresponding to network 2.
     */
    private void unlinkNetworks(WifiConfiguration network1, WifiConfiguration network2) {
        if (network2.linkedConfigurations != null
                && (network2.linkedConfigurations.get(network1.getKey()) != null)) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "unlinkNetworks un-link " + network1.getKey()
                        + " from " + network2.getKey());
            }
            network2.linkedConfigurations.remove(network1.getKey());
        }
        if (network1.linkedConfigurations != null
                && (network1.linkedConfigurations.get(network2.getKey()) != null)) {
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "unlinkNetworks un-link " + network2.getKey()
                        + " from " + network1.getKey());
            }
            network1.linkedConfigurations.remove(network2.getKey());
        }
    }

    /**
     * This method runs through all the saved networks and checks if the provided network can be
     * linked with any of them.
     *
     * @param config WifiConfiguration object corresponding to the network that needs to be
     *               checked for potential links.
     */
    private void attemptNetworkLinking(WifiConfiguration config) {
        // Only link WPA_PSK config.
        if (!config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
            return;
        }
        ScanDetailCache scanDetailCache = getScanDetailCacheForNetwork(config.networkId);
        // Ignore configurations with large number of BSSIDs.
        if (scanDetailCache != null
                && scanDetailCache.size() > LINK_CONFIGURATION_MAX_SCAN_CACHE_ENTRIES) {
            return;
        }
        for (WifiConfiguration linkConfig : getInternalConfiguredNetworks()) {
            if (linkConfig.getKey().equals(config.getKey())) {
                continue;
            }
            if (linkConfig.ephemeral) {
                continue;
            }
            // Network Selector will be allowed to dynamically jump from a linked configuration
            // to another, hence only link configurations that have WPA_PSK security type.
            if (!linkConfig.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
                continue;
            }
            ScanDetailCache linkScanDetailCache =
                    getScanDetailCacheForNetwork(linkConfig.networkId);
            // Ignore configurations with large number of BSSIDs.
            if (linkScanDetailCache != null
                    && linkScanDetailCache.size() > LINK_CONFIGURATION_MAX_SCAN_CACHE_ENTRIES) {
                continue;
            }
            // Check if the networks should be linked/unlinked.
            if (shouldNetworksBeLinked(
                    config, linkConfig, scanDetailCache, linkScanDetailCache)) {
                linkNetworks(config, linkConfig);
            } else {
                unlinkNetworks(config, linkConfig);
            }
        }
    }

    /**
     * Retrieves a list of all the saved hidden networks for scans
     *
     * Hidden network list sent to the firmware has limited size. If there are a lot of saved
     * networks, this list will be truncated and we might end up not sending the networks
     * with the highest chance of connecting to the firmware.
     * So, re-sort the network list based on the frequency of connection to those networks
     * and whether it was last seen in the scan results.
     *
     * @return list of networks in the order of priority.
     */
    public List<WifiScanner.ScanSettings.HiddenNetwork> retrieveHiddenNetworkList() {
        List<WifiScanner.ScanSettings.HiddenNetwork> hiddenList = new ArrayList<>();
        List<WifiConfiguration> networks = getConfiguredNetworks();
        // Remove any non hidden networks.
        networks.removeIf(config -> !config.hiddenSSID);
        networks.sort(mScanListComparator);
        // The most frequently connected network has the highest priority now.
        for (WifiConfiguration config : networks) {
            hiddenList.add(new WifiScanner.ScanSettings.HiddenNetwork(config.SSID));
        }
        return hiddenList;
    }

    /**
     * Check if the provided network was temporarily disabled by the user and still blocked.
     *
     * @param network Input can be SSID or FQDN. And caller must ensure that the SSID passed thru
     *                this API matched the WifiConfiguration.SSID rules, and thus be surrounded by
     *                quotes.
     * @return true if network is blocking, otherwise false.
     */
    public boolean isNetworkTemporarilyDisabledByUser(String network) {
        if (mUserTemporarilyDisabledList.isLocked(network)) {
            return true;
        }
        mUserTemporarilyDisabledList.remove(network);
        return false;
    }

    /**
     * User temporarily disable a network and will be block to auto-join when network is still
     * nearby.
     *
     * The network will be re-enabled when:
     * a) User select to connect the network.
     * b) The network is not in range for {@link #USER_DISCONNECT_NETWORK_BLOCK_EXPIRY_MS}
     * c) Toggle wifi off, reset network settings or device reboot.
     *
     * @param network Input can be SSID or FQDN. And caller must ensure that the SSID passed thru
     *                this API matched the WifiConfiguration.SSID rules, and thus be surrounded by
     *                quotes.
     *        uid     UID of the calling process.
     */
    public void userTemporarilyDisabledNetwork(String network, int uid) {
        mUserTemporarilyDisabledList.add(network, USER_DISCONNECT_NETWORK_BLOCK_EXPIRY_MS);
        Log.d(TAG, "Temporarily disable network: " + network + " uid=" + uid + " num="
                + mUserTemporarilyDisabledList.size());
        removeUserChoiceFromDisabledNetwork(network, uid);
    }

    /**
     * Update the user temporarily disabled network list with networks in range.
     * @param networks networks in range in String format, FQDN or SSID. And caller must ensure
     *                 that the SSID passed thru this API matched the WifiConfiguration.SSID rules,
     *                 and thus be surrounded by quotes.
     */
    public void updateUserDisabledList(List<String> networks) {
        mUserTemporarilyDisabledList.update(new HashSet<>(networks));
    }

    private void removeUserChoiceFromDisabledNetwork(
            @NonNull String network, int uid) {
        for (WifiConfiguration config : getInternalConfiguredNetworks()) {
            if (TextUtils.equals(config.SSID, network) || TextUtils.equals(config.FQDN, network)) {
                if (mWifiPermissionsUtil.checkNetworkSettingsPermission(uid)) {
                    mWifiInjector.getWifiMetrics().logUserActionEvent(
                            UserActionEvent.EVENT_DISCONNECT_WIFI, config.networkId);
                }
                removeConnectChoiceFromAllNetworks(config.getKey());
            }
        }
    }

    /**
     * User enabled network manually, maybe trigger by user select to connect network.
     * @param networkId enabled network id.
     */
    public void userEnabledNetwork(int networkId) {
        WifiConfiguration configuration = getInternalConfiguredNetwork(networkId);
        if (configuration == null) {
            return;
        }
        String network;
        if (configuration.isPasspoint()) {
            network = configuration.FQDN;
        } else {
            network = configuration.SSID;
        }
        mUserTemporarilyDisabledList.remove(network);
        mWifiInjector.getBssidBlocklistMonitor().clearBssidBlocklistForSsid(configuration.SSID);
        Log.d(TAG, "Enable disabled network: " + network + " num="
                + mUserTemporarilyDisabledList.size());
    }

    /**
     * Clear all user temporarily disabled networks.
     */
    public void clearUserTemporarilyDisabledList() {
        mUserTemporarilyDisabledList.clear();
    }

    /**
     * Resets all sim networks state.
     */
    public void resetSimNetworks() {
        if (mVerboseLoggingEnabled) localLog("resetSimNetworks");
        for (WifiConfiguration config : getInternalConfiguredNetworks()) {
            if (config.enterpriseConfig == null
                    || !config.enterpriseConfig.isAuthenticationSimBased()) {
                continue;
            }
            if (config.enterpriseConfig.getEapMethod() == WifiEnterpriseConfig.Eap.PEAP) {
                Pair<String, String> currentIdentity =
                        mWifiCarrierInfoManager.getSimIdentity(config);
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "New identity for config " + config + ": " + currentIdentity);
                }
                // Update the loaded config
                if (currentIdentity == null) {
                    Log.d(TAG, "Identity is null");
                } else {
                    config.enterpriseConfig.setIdentity(currentIdentity.first);
                }
                // do not reset anonymous identity since it may be dependent on user-entry
                // (i.e. cannot re-request on every reboot/SIM re-entry)
            } else {
                // reset identity as well: supplicant will ask us for it
                config.enterpriseConfig.setIdentity("");
                if (!WifiCarrierInfoManager.isAnonymousAtRealmIdentity(
                        config.enterpriseConfig.getAnonymousIdentity())) {
                    config.enterpriseConfig.setAnonymousIdentity("");
                }
            }
        }
    }

    /**
     * Helper method to perform the following operations during user switch/unlock:
     * - Remove private networks of the old user.
     * - Load from the new user store file.
     * - Save the store files again to migrate any user specific networks from the shared store
     *   to user store.
     * This method assumes the user store is visible (i.e CE storage is unlocked). So, the caller
     * should ensure that the stores are accessible before invocation.
     *
     * @param userId The identifier of the new foreground user, after the unlock or switch.
     */
    private void handleUserUnlockOrSwitch(int userId) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Loading from store after user switch/unlock for " + userId);
        }
        // Switch out the user store file.
        if (loadFromUserStoreAfterUnlockOrSwitch(userId)) {
            saveToStore(true);
            mPendingUnlockStoreRead = false;
        }
    }

    /**
     * Handles the switch to a different foreground user:
     * - Flush the current state to the old user's store file.
     * - Switch the user specific store file.
     * - Reload the networks from the store files (shared & user).
     * - Write the store files to move any user specific private networks from shared store to user
     *   store.
     *
     * Need to be called when {@link com.android.server.SystemService#onSwitchUser(int)} is invoked.
     *
     * @param userId The identifier of the new foreground user, after the switch.
     * @return List of network ID's of all the private networks of the old user which will be
     * removed from memory.
     */
    public Set<Integer> handleUserSwitch(int userId) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Handling user switch for " + userId);
        }
        if (userId == mCurrentUserId) {
            Log.w(TAG, "User already in foreground " + userId);
            return new HashSet<>();
        }
        if (mPendingStoreRead) {
            Log.w(TAG, "User switch before store is read!");
            mConfiguredNetworks.setNewUser(userId);
            mCurrentUserId = userId;
            // Reset any state from previous user unlock.
            mDeferredUserUnlockRead = false;
            // Cannot read data from new user's CE store file before they log-in.
            mPendingUnlockStoreRead = true;
            return new HashSet<>();
        }
        if (mUserManager.isUserUnlockingOrUnlocked(UserHandle.of(mCurrentUserId))) {
            saveToStore(true);
        }
        // Remove any private networks of the old user before switching the userId.
        Set<Integer> removedNetworkIds = clearInternalDataForCurrentUser();
        mConfiguredNetworks.setNewUser(userId);
        mCurrentUserId = userId;

        if (mUserManager.isUserUnlockingOrUnlocked(UserHandle.of(mCurrentUserId))) {
            handleUserUnlockOrSwitch(mCurrentUserId);
        } else {
            // Cannot read data from new user's CE store file before they log-in.
            mPendingUnlockStoreRead = true;
            Log.i(TAG, "Waiting for user unlock to load from store");
        }
        return removedNetworkIds;
    }

    /**
     * Handles the unlock of foreground user. This maybe needed to read the store file if the user's
     * CE storage is not visible when {@link #handleUserSwitch(int)} is invoked.
     *
     * Need to be called when {@link com.android.server.SystemService#onUnlockUser(int)} is invoked.
     *
     * @param userId The identifier of the user that unlocked.
     */
    public void handleUserUnlock(int userId) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Handling user unlock for " + userId);
        }
        if (userId != mCurrentUserId) {
            Log.e(TAG, "Ignore user unlock for non current user " + userId);
            return;
        }
        if (mPendingStoreRead) {
            Log.w(TAG, "Ignore user unlock until store is read!");
            mDeferredUserUnlockRead = true;
            return;
        }
        if (mPendingUnlockStoreRead) {
            handleUserUnlockOrSwitch(mCurrentUserId);
        }
    }

    /**
     * Handles the stop of foreground user. This is needed to write the store file to flush
     * out any pending data before the user's CE store storage is unavailable.
     *
     * Need to be called when {@link com.android.server.SystemService#onStopUser(int)} is invoked.
     *
     * @param userId The identifier of the user that stopped.
     */
    public void handleUserStop(int userId) {
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "Handling user stop for " + userId);
        }
        if (userId == mCurrentUserId
                && mUserManager.isUserUnlockingOrUnlocked(UserHandle.of(mCurrentUserId))) {
            saveToStore(true);
            clearInternalDataForCurrentUser();
        }
    }

    /**
     * Helper method to clear internal databases.
     * This method clears the:
     *  - List of configured networks.
     *  - Map of scan detail caches.
     *  - List of deleted ephemeral networks.
     */
    private void clearInternalData() {
        localLog("clearInternalData: Clearing all internal data");
        mConfiguredNetworks.clear();
        mUserTemporarilyDisabledList.clear();
        mRandomizedMacAddressMapping.clear();
        mScanDetailCaches.clear();
        clearLastSelectedNetwork();
    }

    /**
     * Helper method to clear internal databases of the specified user.
     * This method clears the:
     *  - Private configured configured networks of the specified user.
     *  - Map of scan detail caches.
     *  - List of deleted ephemeral networks.
     *
     * @return List of network ID's of all the private networks of the old user which will be
     * removed from memory.
     */
    private Set<Integer> clearInternalDataForCurrentUser() {
        localLog("clearInternalUserData: Clearing user internal data for " + mCurrentUserId);
        Set<Integer> removedNetworkIds = new HashSet<>();
        // Remove any private networks of the old user before switching the userId.
        for (WifiConfiguration config : getConfiguredNetworks()) {
            if (!config.shared && doesUidBelongToCurrentUser(config.creatorUid)) {
                removedNetworkIds.add(config.networkId);
                localLog("clearInternalUserData: removed config."
                        + " netId=" + config.networkId
                        + " configKey=" + config.getKey());
                mConfiguredNetworks.remove(config.networkId);
            }
        }
        mUserTemporarilyDisabledList.clear();
        mScanDetailCaches.clear();
        clearLastSelectedNetwork();
        return removedNetworkIds;
    }

    /**
     * Helper function to populate the internal (in-memory) data from the retrieved shared store
     * (file) data.
     *
     * @param configurations list of configurations retrieved from store.
     */
    private void loadInternalDataFromSharedStore(
            List<WifiConfiguration> configurations,
            Map<String, String> macAddressMapping) {
        for (WifiConfiguration configuration : configurations) {
            configuration.networkId = mNextNetworkId++;
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Adding network from shared store " + configuration.getKey());
            }
            try {
                mConfiguredNetworks.put(configuration);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Failed to add network to config map", e);
            }
        }
        mRandomizedMacAddressMapping.putAll(macAddressMapping);
    }

    /**
     * Helper function to populate the internal (in-memory) data from the retrieved user store
     * (file) data.
     *
     * @param configurations list of configurations retrieved from store.
     */
    private void loadInternalDataFromUserStore(List<WifiConfiguration> configurations) {
        for (WifiConfiguration configuration : configurations) {
            configuration.networkId = mNextNetworkId++;
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Adding network from user store " + configuration.getKey());
            }
            try {
                mConfiguredNetworks.put(configuration);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Failed to add network to config map", e);
            }

            if (configuration.isMostRecentlyConnected) {
                mLruConnectionTracker.addNetwork(configuration);
            }
        }
    }

    /**
     * Initializes the randomized MAC address for an internal WifiConfiguration depending on
     * whether it should use aggressive randomization.
     * @param config
     */
    private void initRandomizedMacForInternalConfig(WifiConfiguration internalConfig) {
        MacAddress randomizedMac = shouldUseAggressiveRandomization(internalConfig)
                ? MacAddressUtils.createRandomUnicastAddress()
                : getPersistentMacAddress(internalConfig);
        if (randomizedMac != null) {
            internalConfig.setRandomizedMacAddress(randomizedMac);
        }
    }

    /**
     * Assign randomized MAC addresses for configured networks.
     * This is needed to generate persistent randomized MAC address for existing networks when
     * a device updates to Q+ for the first time since we are not calling addOrUpdateNetwork when
     * we load configuration at boot.
     */
    private void generateRandomizedMacAddresses() {
        for (WifiConfiguration config : getInternalConfiguredNetworks()) {
            if (DEFAULT_MAC_ADDRESS.equals(config.getRandomizedMacAddress())) {
                initRandomizedMacForInternalConfig(config);
            }
        }
    }

    /**
     * Helper function to populate the internal (in-memory) data from the retrieved stores (file)
     * data.
     * This method:
     * 1. Clears all existing internal data.
     * 2. Sends out the networks changed broadcast after loading all the data.
     *
     * @param sharedConfigurations list of network configurations retrieved from shared store.
     * @param userConfigurations list of network configurations retrieved from user store.
     * @param macAddressMapping
     */
    private void loadInternalData(
            List<WifiConfiguration> sharedConfigurations,
            List<WifiConfiguration> userConfigurations,
            Map<String, String> macAddressMapping) {
        // Clear out all the existing in-memory lists and load the lists from what was retrieved
        // from the config store.
        clearInternalData();
        loadInternalDataFromSharedStore(sharedConfigurations, macAddressMapping);
        loadInternalDataFromUserStore(userConfigurations);
        generateRandomizedMacAddresses();
        if (mConfiguredNetworks.sizeForAllUsers() == 0) {
            Log.w(TAG, "No stored networks found.");
        }
        // reset identity & anonymous identity for networks using SIM-based authentication
        // on load (i.e. boot) so that if the user changed SIMs while the device was powered off,
        // we do not reuse stale credentials that would lead to authentication failure.
        resetSimNetworks();
        sendConfiguredNetworkChangedBroadcast(WifiManager.CHANGE_REASON_ADDED);
        mPendingStoreRead = false;
    }

    /**
     * Read the config store and load the in-memory lists from the store data retrieved and sends
     * out the networks changed broadcast.
     *
     * This reads all the network configurations from:
     * 1. Shared WifiConfigStore.xml
     * 2. User WifiConfigStore.xml
     *
     * @return true on success or not needed (fresh install), false otherwise.
     */
    public boolean loadFromStore() {
        // If the user unlock comes in before we load from store, which means the user store have
        // not been setup yet for the current user. Setup the user store before the read so that
        // configurations for the current user will also being loaded.
        if (mDeferredUserUnlockRead) {
            Log.i(TAG, "Handling user unlock before loading from store.");
            List<WifiConfigStore.StoreFile> userStoreFiles =
                    WifiConfigStore.createUserFiles(
                            mCurrentUserId, mFrameworkFacade.isNiapModeOn(mContext));
            if (userStoreFiles == null) {
                Log.wtf(TAG, "Failed to create user store files");
                return false;
            }
            mWifiConfigStore.setUserStores(userStoreFiles);
            mDeferredUserUnlockRead = false;
        }
        try {
            mWifiConfigStore.read();
        } catch (IOException | IllegalStateException e) {
            Log.wtf(TAG, "Reading from new store failed. All saved networks are lost!", e);
            return false;
        } catch (XmlPullParserException e) {
            Log.wtf(TAG, "XML deserialization of store failed. All saved networks are lost!", e);
            return false;
        }
        loadInternalData(mNetworkListSharedStoreData.getConfigurations(),
                mNetworkListUserStoreData.getConfigurations(),
                mRandomizedMacStoreData.getMacMapping());
        return true;
    }

    /**
     * Read the user config store and load the in-memory lists from the store data retrieved and
     * sends out the networks changed broadcast.
     * This should be used for all user switches/unlocks to only load networks from the user
     * specific store and avoid reloading the shared networks.
     *
     * This reads all the network configurations from:
     * 1. User WifiConfigStore.xml
     *
     * @param userId The identifier of the foreground user.
     * @return true on success, false otherwise.
     */
    private boolean loadFromUserStoreAfterUnlockOrSwitch(int userId) {
        try {
            List<WifiConfigStore.StoreFile> userStoreFiles =
                    WifiConfigStore.createUserFiles(
                            userId, mFrameworkFacade.isNiapModeOn(mContext));
            if (userStoreFiles == null) {
                Log.e(TAG, "Failed to create user store files");
                return false;
            }
            mWifiConfigStore.switchUserStoresAndRead(userStoreFiles);
        } catch (IOException | IllegalStateException e) {
            Log.wtf(TAG, "Reading from new store failed. All saved private networks are lost!", e);
            return false;
        } catch (XmlPullParserException e) {
            Log.wtf(TAG, "XML deserialization of store failed. All saved private networks are "
                    + "lost!", e);
            return false;
        }
        loadInternalDataFromUserStore(mNetworkListUserStoreData.getConfigurations());
        return true;
    }

    /**
     * Save the current snapshot of the in-memory lists to the config store.
     *
     * @param forceWrite Whether the write needs to be forced or not.
     * @return Whether the write was successful or not, this is applicable only for force writes.
     */
    public boolean saveToStore(boolean forceWrite) {
        if (mPendingStoreRead) {
            Log.e(TAG, "Cannot save to store before store is read!");
            return false;
        }
        ArrayList<WifiConfiguration> sharedConfigurations = new ArrayList<>();
        ArrayList<WifiConfiguration> userConfigurations = new ArrayList<>();
        // List of network IDs for legacy Passpoint configuration to be removed.
        List<Integer> legacyPasspointNetId = new ArrayList<>();
        for (WifiConfiguration config : mConfiguredNetworks.valuesForAllUsers()) {
            // Ignore ephemeral networks and non-legacy Passpoint configurations.
            if (config.ephemeral || (config.isPasspoint() && !config.isLegacyPasspointConfig)) {
                continue;
            }

            // Migrate the legacy Passpoint configurations owned by the current user to
            // {@link PasspointManager}.
            if (config.isLegacyPasspointConfig && doesUidBelongToCurrentUser(config.creatorUid)) {
                legacyPasspointNetId.add(config.networkId);
                // Migrate the legacy Passpoint configuration and add it to PasspointManager.
                if (!PasspointManager.addLegacyPasspointConfig(config)) {
                    Log.e(TAG, "Failed to migrate legacy Passpoint config: " + config.FQDN);
                }
                // This will prevent adding |config| to the |sharedConfigurations|.
                continue;
            }

            config.isMostRecentlyConnected =
                    mLruConnectionTracker.isMostRecentlyConnected(config);

            // We push all shared networks & private networks not belonging to the current
            // user to the shared store. Ideally, private networks for other users should
            // not even be in memory,
            // But, this logic is in place to deal with store migration from N to O
            // because all networks were previously stored in a central file. We cannot
            // write these private networks to the user specific store until the corresponding
            // user logs in.
            if (config.shared || !doesUidBelongToCurrentUser(config.creatorUid)) {
                sharedConfigurations.add(config);
            } else {
                userConfigurations.add(config);
            }
        }

        // Remove the configurations for migrated Passpoint configurations.
        for (int networkId : legacyPasspointNetId) {
            mConfiguredNetworks.remove(networkId);
        }

        // Setup store data for write.
        mNetworkListSharedStoreData.setConfigurations(sharedConfigurations);
        mNetworkListUserStoreData.setConfigurations(userConfigurations);
        mRandomizedMacStoreData.setMacMapping(mRandomizedMacAddressMapping);

        try {
            mWifiConfigStore.write(forceWrite);
        } catch (IOException | IllegalStateException e) {
            Log.wtf(TAG, "Writing to store failed. Saved networks maybe lost!", e);
            return false;
        } catch (XmlPullParserException e) {
            Log.wtf(TAG, "XML serialization for store failed. Saved networks maybe lost!", e);
            return false;
        }
        return true;
    }

    /**
     * Helper method for logging into local log buffer.
     */
    private void localLog(String s) {
        if (mLocalLog != null) {
            mLocalLog.log(s);
        }
    }

    /**
     * Dump the local log buffer and other internal state of WifiConfigManager.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiConfigManager");
        pw.println("WifiConfigManager - Log Begin ----");
        mLocalLog.dump(fd, pw, args);
        pw.println("WifiConfigManager - Log End ----");
        pw.println("WifiConfigManager - Configured networks Begin ----");
        for (WifiConfiguration network : getInternalConfiguredNetworks()) {
            pw.println(network);
        }
        pw.println("WifiConfigManager - Configured networks End ----");
        pw.println("WifiConfigManager - ConfigurationMap Begin ----");
        mConfiguredNetworks.dump(fd, pw, args);
        pw.println("WifiConfigManager - ConfigurationMap End ----");
        pw.println("WifiConfigManager - Next network ID to be allocated " + mNextNetworkId);
        pw.println("WifiConfigManager - Last selected network ID " + mLastSelectedNetworkId);
        pw.println("WifiConfigManager - PNO scan frequency culling enabled = "
                + mContext.getResources().getBoolean(R.bool.config_wifiPnoFrequencyCullingEnabled));
        pw.println("WifiConfigManager - PNO scan recency sorting enabled = "
                + mContext.getResources().getBoolean(R.bool.config_wifiPnoRecencySortingEnabled));
        mWifiConfigStore.dump(fd, pw, args);
        mWifiCarrierInfoManager.dump(fd, pw, args);
    }

    /**
     * Returns true if the given uid has permission to add, update or remove proxy settings
     */
    private boolean canModifyProxySettings(int uid, String packageName) {
        final boolean isDeviceOwner = mWifiPermissionsUtil.isDeviceOwner(uid, packageName);
        final boolean isProfileOwner = mWifiPermissionsUtil.isProfileOwner(uid, packageName);
        final boolean hasNetworkSettingsPermission =
                mWifiPermissionsUtil.checkNetworkSettingsPermission(uid);
        final boolean hasNetworkSetupWizardPermission =
                mWifiPermissionsUtil.checkNetworkSetupWizardPermission(uid);
        final boolean hasNetworkManagedProvisioningPermission =
                mWifiPermissionsUtil.checkNetworkManagedProvisioningPermission(uid);
        // If |uid| corresponds to the device owner, allow all modifications.
        if (isProfileOwner || isDeviceOwner || hasNetworkSettingsPermission
                || hasNetworkSetupWizardPermission || hasNetworkManagedProvisioningPermission) {
            return true;
        }
        if (mVerboseLoggingEnabled) {
            Log.v(TAG, "UID: " + uid + " cannot modify WifiConfiguration proxy settings."
                    + " hasNetworkSettings=" + hasNetworkSettingsPermission
                    + " hasNetworkSetupWizard=" + hasNetworkSetupWizardPermission
                    + " DeviceOwner=" + isDeviceOwner
                    + " ProfileOwner=" + isProfileOwner);
        }
        return false;
    }

    /**
     * Add the network update event listener
     */
    public void addOnNetworkUpdateListener(OnNetworkUpdateListener listener) {
        mListeners.add(listener);
    }

    /**
     * Set extra failure reason for given config. Used to surface extra failure details to the UI
     * @param netId The network ID of the config to set the extra failure reason for
     * @param reason the WifiConfiguration.ExtraFailureReason failure code representing the most
     *               recent failure reason
     */
    public void setRecentFailureAssociationStatus(int netId, int reason) {
        WifiConfiguration config = getInternalConfiguredNetwork(netId);
        if (config == null) {
            return;
        }
        config.recentFailure.setAssociationStatus(reason);
    }

    /**
     * @param netId The network ID of the config to clear the extra failure reason from
     */
    public void clearRecentFailureReason(int netId) {
        WifiConfiguration config = getInternalConfiguredNetwork(netId);
        if (config == null) {
            return;
        }
        config.recentFailure.clear();
    }

    /**
     * Find the highest RSSI among all valid scanDetails in current network's scanDetail cache.
     * If scanDetail is too old, it is not considered to be valid.
     * @param netId The network ID of the config to find scan RSSI
     * @params scanRssiValidTimeMs The valid time for scan RSSI
     * @return The highest RSSI in dBm found with current network's scanDetail cache.
     */
    public int findScanRssi(int netId, int scanRssiValidTimeMs) {
        int scanMaxRssi = WifiInfo.INVALID_RSSI;
        ScanDetailCache scanDetailCache = getScanDetailCacheForNetwork(netId);
        if (scanDetailCache == null || scanDetailCache.size() == 0) return scanMaxRssi;
        long nowInMillis = mClock.getWallClockMillis();
        for (ScanDetail scanDetail : scanDetailCache.values()) {
            ScanResult result = scanDetail.getScanResult();
            if (result == null) continue;
            boolean valid = (nowInMillis - result.seen) < scanRssiValidTimeMs;

            if (valid) {
                scanMaxRssi = Math.max(scanMaxRssi, result.level);
            }
        }
        return scanMaxRssi;
    }

    public Comparator<WifiConfiguration> getScanListComparator() {
        return mScanListComparator;
    }
}
