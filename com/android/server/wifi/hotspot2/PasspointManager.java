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

package com.android.server.wifi.hotspot2;

import static android.app.AppOpsManager.OPSTR_CHANGE_WIFI_STATE;
import static android.net.wifi.WifiConfiguration.MeteredOverride;

import static java.security.cert.PKIXReason.NO_TRUST_ANCHOR;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.server.wifi.Clock;
import com.android.server.wifi.NetworkUpdateResult;
import com.android.server.wifi.WifiCarrierInfoManager;
import com.android.server.wifi.WifiConfigManager;
import com.android.server.wifi.WifiConfigStore;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.WifiKeyStore;
import com.android.server.wifi.WifiMetrics;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants;
import com.android.server.wifi.hotspot2.anqp.HSOsuProvidersElement;
import com.android.server.wifi.hotspot2.anqp.OsuProviderInfo;
import com.android.server.wifi.proto.nano.WifiMetricsProto.UserActionEvent;
import com.android.server.wifi.util.InformationElementUtil;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This class provides the APIs to manage Passpoint provider configurations.
 * It deals with the following:
 * - Maintaining a list of configured Passpoint providers for provider matching.
 * - Persisting the providers configurations to store when required.
 * - matching Passpoint providers based on the scan results
 * - Supporting WifiManager Public API calls:
 *   > addOrUpdatePasspointConfiguration()
 *   > removePasspointConfiguration()
 *   > getPasspointConfigurations()
 *
 * The provider matching requires obtaining additional information from the AP (ANQP elements).
 * The ANQP elements will be cached using {@link AnqpCache} to avoid unnecessary requests.
 *
 * NOTE: These API's are not thread safe and should only be used from the main Wifi thread.
 */
public class PasspointManager {
    private static final String TAG = "PasspointManager";

    /**
     * Handle for the current {@link PasspointManager} instance.  This is needed to avoid
     * circular dependency with the WifiConfigManger, it will be used for adding the
     * legacy Passpoint configurations.
     *
     * This can be eliminated once we can remove the dependency for WifiConfigManager (for
     * triggering config store write) from this class.
     */
    private static PasspointManager sPasspointManager;

    private final PasspointEventHandler mPasspointEventHandler;
    private final WifiInjector mWifiInjector;
    private final Handler mHandler;
    private final WifiKeyStore mKeyStore;
    private final PasspointObjectFactory mObjectFactory;

    private final Map<String, PasspointProvider> mProviders;
    private final AnqpCache mAnqpCache;
    private final ANQPRequestManager mAnqpRequestManager;
    private final WifiConfigManager mWifiConfigManager;
    private final WifiMetrics mWifiMetrics;
    private final PasspointProvisioner mPasspointProvisioner;
    private final AppOpsManager mAppOps;
    private final WifiCarrierInfoManager mWifiCarrierInfoManager;

    /**
     * Map of package name of an app to the app ops changed listener for the app.
     */
    private final Map<String, AppOpsChangedListener> mAppOpsChangedListenerPerApp = new HashMap<>();

    // Counter used for assigning unique identifier to each provider.
    private long mProviderIndex;
    private boolean mVerboseLoggingEnabled = false;

    private class CallbackHandler implements PasspointEventHandler.Callbacks {
        private final Context mContext;
        CallbackHandler(Context context) {
            mContext = context;
        }

        @Override
        public void onANQPResponse(long bssid,
                Map<Constants.ANQPElementType, ANQPElement> anqpElements) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "ANQP response received from BSSID "
                        + Utils.macToString(bssid));
            }
            // Notify request manager for the completion of a request.
            ANQPNetworkKey anqpKey =
                    mAnqpRequestManager.onRequestCompleted(bssid, anqpElements != null);
            if (anqpElements == null || anqpKey == null) {
                // Query failed or the request wasn't originated from us (not tracked by the
                // request manager). Nothing to be done.
                return;
            }

            // Add new entry to the cache.
            mAnqpCache.addEntry(anqpKey, anqpElements);
        }

        @Override
        public void onIconResponse(long bssid, String fileName, byte[] data) {
            // Empty
        }

        @Override
        public void onWnmFrameReceived(WnmData event) {
            // Empty
        }
    }

    /**
     * Data provider for the Passpoint configuration store data
     * {@link PasspointConfigUserStoreData}.
     */
    private class UserDataSourceHandler implements PasspointConfigUserStoreData.DataSource {
        @Override
        public List<PasspointProvider> getProviders() {
            List<PasspointProvider> providers = new ArrayList<>();
            for (Map.Entry<String, PasspointProvider> entry : mProviders.entrySet()) {
                providers.add(entry.getValue());
            }
            return providers;
        }

        @Override
        public void setProviders(List<PasspointProvider> providers) {
            mProviders.clear();
            for (PasspointProvider provider : providers) {
                provider.enableVerboseLogging(mVerboseLoggingEnabled ? 1 : 0);
                mProviders.put(provider.getConfig().getUniqueId(), provider);
                if (provider.getPackageName() != null) {
                    startTrackingAppOpsChange(provider.getPackageName(),
                            provider.getCreatorUid());
                }
            }
        }
    }

    /**
     * Data provider for the Passpoint configuration store data
     * {@link PasspointConfigSharedStoreData}.
     */
    private class SharedDataSourceHandler implements PasspointConfigSharedStoreData.DataSource {
        @Override
        public long getProviderIndex() {
            return mProviderIndex;
        }

        @Override
        public void setProviderIndex(long providerIndex) {
            mProviderIndex = providerIndex;
        }
    }

    /**
     * Listener for app-ops changes for apps to remove the corresponding Passpoint profiles.
     */
    private final class AppOpsChangedListener implements AppOpsManager.OnOpChangedListener {
        private final String mPackageName;
        private final int mUid;

        AppOpsChangedListener(@NonNull String packageName, int uid) {
            mPackageName = packageName;
            mUid = uid;
        }

        @Override
        public void onOpChanged(String op, String packageName) {
            mHandler.post(() -> {
                if (!mPackageName.equals(packageName)) return;
                if (!OPSTR_CHANGE_WIFI_STATE.equals(op)) return;

                // Ensures the uid to package mapping is still correct.
                try {
                    mAppOps.checkPackage(mUid, mPackageName);
                } catch (SecurityException e) {
                    Log.wtf(TAG, "Invalid uid/package" + packageName);
                    return;
                }
                if (mAppOps.unsafeCheckOpNoThrow(OPSTR_CHANGE_WIFI_STATE, mUid, mPackageName)
                        == AppOpsManager.MODE_IGNORED) {
                    Log.i(TAG, "User disallowed change wifi state for " + packageName);

                    // Removes the profiles installed by the app from database.
                    removePasspointProviderWithPackage(mPackageName);
                }
            });
        }
    }

    /**
     * Remove all Passpoint profiles installed by the app that has been disabled or uninstalled.
     *
     * @param packageName Package name of the app to remove the corresponding Passpoint profiles.
     */
    public void removePasspointProviderWithPackage(@NonNull String packageName) {
        stopTrackingAppOpsChange(packageName);
        for (Map.Entry<String, PasspointProvider> entry : getPasspointProviderWithPackage(
                packageName).entrySet()) {
            String uniqueId = entry.getValue().getConfig().getUniqueId();
            removeProvider(Process.WIFI_UID /* ignored */, true, uniqueId, null);
            disconnectIfPasspointNetwork(uniqueId);
        }
    }

    private Map<String, PasspointProvider> getPasspointProviderWithPackage(
            @NonNull String packageName) {
        return mProviders.entrySet().stream().filter(
                entry -> TextUtils.equals(packageName,
                        entry.getValue().getPackageName())).collect(
                Collectors.toMap(entry -> entry.getKey(), entry -> entry.getValue()));
    }

    private void startTrackingAppOpsChange(@NonNull String packageName, int uid) {
        // The package is already registered.
        if (mAppOpsChangedListenerPerApp.containsKey(packageName)) return;
        AppOpsChangedListener appOpsChangedListener = new AppOpsChangedListener(packageName, uid);
        mAppOps.startWatchingMode(OPSTR_CHANGE_WIFI_STATE, packageName, appOpsChangedListener);
        mAppOpsChangedListenerPerApp.put(packageName, appOpsChangedListener);
    }

    private void stopTrackingAppOpsChange(@NonNull String packageName) {
        AppOpsChangedListener appOpsChangedListener = mAppOpsChangedListenerPerApp.remove(
                packageName);
        if (appOpsChangedListener == null) {
            Log.i(TAG, "No app ops listener found for " + packageName);
            return;
        }
        mAppOps.stopWatchingMode(appOpsChangedListener);
    }

    private void disconnectIfPasspointNetwork(String uniqueId) {
        WifiConfiguration currentConfiguration =
                mWifiInjector.getClientModeImpl().getCurrentWifiConfiguration();
        if (currentConfiguration == null) return;
        if (currentConfiguration.isPasspoint() && TextUtils.equals(currentConfiguration.getKey(),
                uniqueId)) {
            Log.i(TAG, "Disconnect current Passpoint network for FQDN: "
                    + currentConfiguration.FQDN + " and ID: " + uniqueId
                    + " because the profile was removed");
            mWifiInjector.getClientModeImpl().disconnectCommand();
        }
    }

    public PasspointManager(Context context, WifiInjector wifiInjector, Handler handler,
            WifiNative wifiNative, WifiKeyStore keyStore, Clock clock,
            PasspointObjectFactory objectFactory, WifiConfigManager wifiConfigManager,
            WifiConfigStore wifiConfigStore,
            WifiMetrics wifiMetrics,
            WifiCarrierInfoManager wifiCarrierInfoManager) {
        mPasspointEventHandler = objectFactory.makePasspointEventHandler(wifiNative,
                new CallbackHandler(context));
        mWifiInjector = wifiInjector;
        mHandler = handler;
        mKeyStore = keyStore;
        mObjectFactory = objectFactory;
        mProviders = new HashMap<>();
        mAnqpCache = objectFactory.makeAnqpCache(clock);
        mAnqpRequestManager = objectFactory.makeANQPRequestManager(mPasspointEventHandler, clock);
        mWifiConfigManager = wifiConfigManager;
        mWifiMetrics = wifiMetrics;
        mProviderIndex = 0;
        mWifiCarrierInfoManager = wifiCarrierInfoManager;
        wifiConfigStore.registerStoreData(objectFactory.makePasspointConfigUserStoreData(
                mKeyStore, mWifiCarrierInfoManager, new UserDataSourceHandler()));
        wifiConfigStore.registerStoreData(objectFactory.makePasspointConfigSharedStoreData(
                new SharedDataSourceHandler()));
        mPasspointProvisioner = objectFactory.makePasspointProvisioner(context, wifiNative,
                this, wifiMetrics);
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        sPasspointManager = this;
    }

    /**
     * Initializes the provisioning flow with a looper.
     * This looper should be tied to a background worker thread since PasspointProvisioner has a
     * heavy workload.
     */
    public void initializeProvisioner(Looper looper) {
        mPasspointProvisioner.init(looper);
    }

    /**
     * Enable verbose logging
     * @param verbose more than 0 enables verbose logging
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = (verbose > 0) ? true : false;
        mPasspointProvisioner.enableVerboseLogging(verbose);
        for (PasspointProvider provider : mProviders.values()) {
            provider.enableVerboseLogging(verbose);
        }
    }

    private void updateWifiConfigInWcmIfPresent(
            WifiConfiguration newConfig, int uid, String packageName, boolean isFromSuggestion) {
        WifiConfiguration configInWcm =
                mWifiConfigManager.getConfiguredNetwork(newConfig.getKey());
        if (configInWcm == null) return;
        // suggestion != saved
        if (isFromSuggestion != configInWcm.fromWifiNetworkSuggestion) return;
        // is suggestion from same app.
        if (isFromSuggestion
                && (configInWcm.creatorUid != uid
                || !TextUtils.equals(configInWcm.creatorName, packageName))) {
            return;
        }
        NetworkUpdateResult result = mWifiConfigManager.addOrUpdateNetwork(
                newConfig, uid, packageName);
        if (!result.isSuccess()) {
            Log.e(TAG, "Failed to update config in WifiConfigManager");
        } else {
            mWifiConfigManager.allowAutojoin(result.getNetworkId(), newConfig.allowAutojoin);
            if (mVerboseLoggingEnabled) {
                Log.v(TAG, "Updated config in WifiConfigManager");
            }
        }
    }

    /**
     * Add or update a Passpoint provider with the given configuration.
     *
     * Each provider is uniquely identified by its unique identifier, see
     * {@link PasspointConfiguration#getUniqueId()}.
     * In the case when there is an existing configuration with the same unique identifier,
     * a provider with the new configuration will replace the existing provider.
     *
     * @param config Configuration of the Passpoint provider to be added
     * @param uid Uid of the app adding/Updating {@code config}
     * @param packageName Package name of the app adding/Updating {@code config}
     * @param isFromSuggestion Whether this {@code config} is from suggestion API
     * @param isTrusted Whether this {@code config} an trusted network, default should be true.
     *                  Only able set to false when {@code isFromSuggestion} is true, otherwise
     *                  adding {@code config} will be false.
     * @return true if provider is added, false otherwise
     */
    public boolean addOrUpdateProvider(PasspointConfiguration config, int uid,
            String packageName, boolean isFromSuggestion, boolean isTrusted) {
        mWifiMetrics.incrementNumPasspointProviderInstallation();
        if (config == null) {
            Log.e(TAG, "Configuration not provided");
            return false;
        }
        if (!config.validate()) {
            Log.e(TAG, "Invalid configuration");
            return false;
        }
        if (!(isFromSuggestion || isTrusted)) {
            Log.e(TAG, "Set isTrusted to false on a non suggestion passpoint is not allowed");
            return false;
        }

        mWifiCarrierInfoManager.tryUpdateCarrierIdForPasspoint(config);
        // Create a provider and install the necessary certificates and keys.
        PasspointProvider newProvider = mObjectFactory.makePasspointProvider(config, mKeyStore,
                mWifiCarrierInfoManager, mProviderIndex++, uid, packageName, isFromSuggestion);
        newProvider.setTrusted(isTrusted);

        boolean metricsNoRootCa = false;
        boolean metricsSelfSignedRootCa = false;
        boolean metricsSubscriptionExpiration = false;

        if (config.getCredential().getUserCredential() != null
                || config.getCredential().getCertCredential() != null) {
            X509Certificate[] x509Certificates = config.getCredential().getCaCertificates();
            if (x509Certificates == null) {
                metricsNoRootCa = true;
            } else {
                try {
                    for (X509Certificate certificate : x509Certificates) {
                        verifyCaCert(certificate);
                    }
                } catch (CertPathValidatorException e) {
                    // A self signed Root CA will fail path validation checks with NO_TRUST_ANCHOR
                    if (e.getReason() == NO_TRUST_ANCHOR) {
                        metricsSelfSignedRootCa = true;
                    }
                } catch (Exception e) {
                    // Other exceptions, fall through, will be handled below
                }
            }
        }
        if (config.getSubscriptionExpirationTimeMillis() != Long.MIN_VALUE) {
            metricsSubscriptionExpiration = true;
        }

        if (!newProvider.installCertsAndKeys()) {
            Log.e(TAG, "Failed to install certificates and keys to keystore");
            return false;
        }

        // Remove existing provider with the same unique ID.
        if (mProviders.containsKey(config.getUniqueId())) {
            PasspointProvider old = mProviders.get(config.getUniqueId());
            // If new profile is from suggestion and from a different App, ignore new profile,
            // return false.
            // If from same app, update it.
            if (isFromSuggestion && !old.getPackageName().equals(packageName)) {
                newProvider.uninstallCertsAndKeys();
                return false;
            }
            Log.d(TAG, "Replacing configuration for FQDN: " + config.getHomeSp().getFqdn()
                    + " and unique ID: " + config.getUniqueId());
            old.uninstallCertsAndKeys();
            mProviders.remove(config.getUniqueId());
            // New profile changes the credential, remove the related WifiConfig.
            if (!old.equals(newProvider)) {
                mWifiConfigManager.removePasspointConfiguredNetwork(
                        newProvider.getWifiConfig().getKey());
            } else {
                // If there is a config cached in WifiConfigManager, update it with new info.
                updateWifiConfigInWcmIfPresent(
                        newProvider.getWifiConfig(), uid, packageName, isFromSuggestion);
            }
        }
        newProvider.enableVerboseLogging(mVerboseLoggingEnabled ? 1 : 0);
        mProviders.put(config.getUniqueId(), newProvider);
        mWifiConfigManager.saveToStore(true /* forceWrite */);
        if (!isFromSuggestion && newProvider.getPackageName() != null) {
            startTrackingAppOpsChange(newProvider.getPackageName(), uid);
        }
        Log.d(TAG, "Added/updated Passpoint configuration for FQDN: "
                + config.getHomeSp().getFqdn() + " with unique ID: " + config.getUniqueId()
                + " by UID: " + uid);
        if (metricsNoRootCa) {
            mWifiMetrics.incrementNumPasspointProviderWithNoRootCa();
        }
        if (metricsSelfSignedRootCa) {
            mWifiMetrics.incrementNumPasspointProviderWithSelfSignedRootCa();
        }
        if (metricsSubscriptionExpiration) {
            mWifiMetrics.incrementNumPasspointProviderWithSubscriptionExpiration();
        }
        mWifiMetrics.incrementNumPasspointProviderInstallSuccess();
        return true;
    }

    private boolean removeProviderInternal(PasspointProvider provider, int callingUid,
            boolean privileged) {
        if (!privileged && callingUid != provider.getCreatorUid()) {
            Log.e(TAG, "UID " + callingUid + " cannot remove profile created by "
                    + provider.getCreatorUid());
            return false;
        }
        provider.uninstallCertsAndKeys();
        String packageName = provider.getPackageName();
        // Remove any configs corresponding to the profile in WifiConfigManager.
        mWifiConfigManager.removePasspointConfiguredNetwork(
                provider.getWifiConfig().getKey());
        String uniqueId = provider.getConfig().getUniqueId();
        mProviders.remove(uniqueId);
        mWifiConfigManager.saveToStore(true /* forceWrite */);

        // Stop monitoring the package if there is no Passpoint profile installed by the package
        if (mAppOpsChangedListenerPerApp.containsKey(packageName)
                && getPasspointProviderWithPackage(packageName).size() == 0) {
            stopTrackingAppOpsChange(packageName);
        }
        Log.d(TAG, "Removed Passpoint configuration: " + uniqueId);
        mWifiMetrics.incrementNumPasspointProviderUninstallSuccess();
        return true;
    }

    /**
     * Remove a Passpoint provider identified by the given its unique identifier.
     *
     * @param callingUid Calling UID.
     * @param privileged Whether the caller is a privileged entity
     * @param uniqueId The ID of the provider to remove. Not required if FQDN is specified.
     * @param fqdn The FQDN of the provider to remove. Not required if unique ID is specified.
     * @return true if a provider is removed, false otherwise
     */
    public boolean removeProvider(int callingUid, boolean privileged, String uniqueId,
            String fqdn) {
        if (uniqueId == null && fqdn == null) {
            mWifiMetrics.incrementNumPasspointProviderUninstallation();
            Log.e(TAG, "Cannot remove provider, both FQDN and unique ID are null");
            return false;
        }

        if (uniqueId != null) {
            // Unique identifier provided
            mWifiMetrics.incrementNumPasspointProviderUninstallation();
            PasspointProvider provider = mProviders.get(uniqueId);
            if (provider == null) {
                Log.e(TAG, "Config doesn't exist");
                return false;
            }
            return removeProviderInternal(provider, callingUid, privileged);
        }

        // FQDN provided, loop through all profiles with matching FQDN
        ArrayList<PasspointProvider> passpointProviders = new ArrayList<>(mProviders.values());
        int removedProviders = 0;
        int numOfUninstallations = 0;
        for (PasspointProvider provider : passpointProviders) {
            if (!TextUtils.equals(provider.getConfig().getHomeSp().getFqdn(), fqdn)) {
                continue;
            }
            mWifiMetrics.incrementNumPasspointProviderUninstallation();
            numOfUninstallations++;
            if (removeProviderInternal(provider, callingUid, privileged)) {
                removedProviders++;
            }
        }

        if (numOfUninstallations == 0) {
            // Update uninstallation requests metrics here to cover the corner case of trying to
            // uninstall a non-existent provider.
            mWifiMetrics.incrementNumPasspointProviderUninstallation();
        }

        return removedProviders > 0;
    }

    /**
     * Enable or disable the auto-join configuration. Auto-join controls whether or not the
     * passpoint configuration is used for auto connection (network selection). Note that even
     * when auto-join is disabled the configuration can still be used for manual connection.
     *
     * @param uniqueId The unique identifier of the configuration. Not required if FQDN is specified
     * @param fqdn The FQDN of the configuration. Not required if uniqueId is specified.
     * @param enableAutojoin true to enable auto-join, false to disable.
     * @return true on success, false otherwise (e.g. if no such provider exists).
     */
    public boolean enableAutojoin(String uniqueId, String fqdn, boolean enableAutojoin) {
        if (uniqueId == null && fqdn == null) {
            return false;
        }
        if (uniqueId != null) {
            // Unique identifier provided
            PasspointProvider provider = mProviders.get(uniqueId);
            if (provider == null) {
                Log.e(TAG, "Config doesn't exist");
                return false;
            }
            if (provider.setAutojoinEnabled(enableAutojoin)) {
                mWifiMetrics.logUserActionEvent(enableAutojoin
                                ? UserActionEvent.EVENT_CONFIGURE_AUTO_CONNECT_ON
                                : UserActionEvent.EVENT_CONFIGURE_AUTO_CONNECT_OFF,
                        provider.isFromSuggestion(), true);
            }
            mWifiConfigManager.saveToStore(true);
            return true;
        }

        ArrayList<PasspointProvider> passpointProviders = new ArrayList<>(mProviders.values());
        boolean found = false;

        // FQDN provided, loop through all profiles with matching FQDN
        for (PasspointProvider provider : passpointProviders) {
            if (TextUtils.equals(provider.getConfig().getHomeSp().getFqdn(), fqdn)) {
                if (provider.setAutojoinEnabled(enableAutojoin)) {
                    mWifiMetrics.logUserActionEvent(enableAutojoin
                                    ? UserActionEvent.EVENT_CONFIGURE_AUTO_CONNECT_ON
                                    : UserActionEvent.EVENT_CONFIGURE_AUTO_CONNECT_OFF,
                            provider.isFromSuggestion(), true);
                }
                found = true;
            }
        }
        if (found) {
            mWifiConfigManager.saveToStore(true);
        }
        return found;
    }

    /**
     * Enable or disable MAC randomization for this passpoint profile.
     * @param fqdn The FQDN of the configuration
     * @param enable true to enable MAC randomization, false to disable
     * @return true on success, false otherwise (e.g. if no such provider exists).
     */
    public boolean enableMacRandomization(@NonNull String fqdn, boolean enable) {
        ArrayList<PasspointProvider> passpointProviders = new ArrayList<>(mProviders.values());
        boolean found = false;

        // Loop through all profiles with matching FQDN
        for (PasspointProvider provider : passpointProviders) {
            if (TextUtils.equals(provider.getConfig().getHomeSp().getFqdn(), fqdn)) {
                boolean settingChanged = provider.setMacRandomizationEnabled(enable);
                if (settingChanged) {
                    mWifiMetrics.logUserActionEvent(enable
                                    ? UserActionEvent.EVENT_CONFIGURE_MAC_RANDOMIZATION_ON
                                    : UserActionEvent.EVENT_CONFIGURE_MAC_RANDOMIZATION_OFF,
                            provider.isFromSuggestion(), true);
                    mWifiConfigManager.removePasspointConfiguredNetwork(
                            provider.getWifiConfig().getKey());
                }
                found = true;
            }
        }
        if (found) {
            mWifiConfigManager.saveToStore(true);
        }
        return found;
    }

    /**
     * Set the metered override value for this passpoint profile
     * @param fqdn The FQDN of the configuration
     * @param meteredOverride One of the values in {@link MeteredOverride}
     * @return true on success, false otherwise (e.g. if no such provider exists).
     */
    public boolean setMeteredOverride(@NonNull String fqdn, @MeteredOverride int meteredOverride) {
        ArrayList<PasspointProvider> passpointProviders = new ArrayList<>(mProviders.values());
        boolean found = false;

        // Loop through all profiles with matching FQDN
        for (PasspointProvider provider : passpointProviders) {
            if (TextUtils.equals(provider.getConfig().getHomeSp().getFqdn(), fqdn)) {
                if (provider.setMeteredOverride(meteredOverride)) {
                    mWifiMetrics.logUserActionEvent(
                            WifiMetrics.convertMeteredOverrideEnumToUserActionEventType(
                                    meteredOverride),
                            provider.isFromSuggestion(), true);
                }
                found = true;
            }
        }
        if (found) {
            mWifiConfigManager.saveToStore(true);
        }
        return found;
    }

    /**
     * Return the installed Passpoint provider configurations.
     * An empty list will be returned when no provider is installed.
     *
     * @param callingUid Calling UID.
     * @param privileged Whether the caller is a privileged entity
     * @return A list of {@link PasspointConfiguration}
     */
    public List<PasspointConfiguration> getProviderConfigs(int callingUid,
            boolean privileged) {
        List<PasspointConfiguration> configs = new ArrayList<>();
        for (Map.Entry<String, PasspointProvider> entry : mProviders.entrySet()) {
            PasspointProvider provider = entry.getValue();
            if (privileged || callingUid == provider.getCreatorUid()) {
                if (provider.isFromSuggestion()) {
                    continue;
                }
                configs.add(provider.getConfig());
            }
        }
        return configs;
    }

    /**
     * Find all providers that can provide service through the given AP, which means the
     * providers contained credential to authenticate with the given AP.
     *
     * If there is any home provider available, will return a list of matched home providers.
     * Otherwise will return a list of matched roaming providers.
     *
     * A empty list will be returned if no matching is found.
     *
     * @param scanResult The scan result associated with the AP
     * @return a list of pairs of {@link PasspointProvider} and match status.
     */
    public @NonNull List<Pair<PasspointProvider, PasspointMatch>> matchProvider(
            ScanResult scanResult) {
        return matchProvider(scanResult, true);
    }

    /**
     * Find all providers that can provide service through the given AP, which means the
     * providers contained credential to authenticate with the given AP.
     *
     * If there is any home provider available, will return a list of matched home providers.
     * Otherwise will return a list of matched roaming providers.
     *
     * A empty list will be returned if no matching is found.
     *
     * @param scanResult The scan result associated with the AP
     * @param anqpRequestAllowed Indicates if to allow ANQP request if the provider's entry is empty
     * @return a list of pairs of {@link PasspointProvider} and match status.
     */
    public @NonNull List<Pair<PasspointProvider, PasspointMatch>> matchProvider(
            ScanResult scanResult, boolean anqpRequestAllowed) {
        List<Pair<PasspointProvider, PasspointMatch>> allMatches = getAllMatchedProviders(
                scanResult, anqpRequestAllowed);
        if (allMatches.isEmpty()) {
            return allMatches;
        }
        List<Pair<PasspointProvider, PasspointMatch>> homeProviders = new ArrayList<>();
        List<Pair<PasspointProvider, PasspointMatch>> roamingProviders = new ArrayList<>();
        for (Pair<PasspointProvider, PasspointMatch> match : allMatches) {
            if (isExpired(match.first.getConfig())) {
                continue;
            }
            if (match.second == PasspointMatch.HomeProvider) {
                homeProviders.add(match);
            } else {
                roamingProviders.add(match);
            }
        }

        if (!homeProviders.isEmpty()) {
            Log.d(TAG, String.format("Matched %s to %s providers as %s", scanResult.SSID,
                    homeProviders.size(), "Home Provider"));
            return homeProviders;
        }

        if (!roamingProviders.isEmpty()) {
            Log.d(TAG, String.format("Matched %s to %s providers as %s", scanResult.SSID,
                    roamingProviders.size(), "Roaming Provider"));
            return roamingProviders;
        }

        if (mVerboseLoggingEnabled) {
            Log.d(TAG, "No service provider found for " + scanResult.SSID);
        }
        return new ArrayList<>();
    }

    /**
     * Return a list of all providers that can provide service through the given AP.
     *
     * @param scanResult The scan result associated with the AP
     * @return a list of pairs of {@link PasspointProvider} and match status.
     */
    public @NonNull List<Pair<PasspointProvider, PasspointMatch>> getAllMatchedProviders(
            ScanResult scanResult) {
        return getAllMatchedProviders(scanResult, true);
    }

    /**
     * Return a list of all providers that can provide service through the given AP.
     *
     * @param scanResult The scan result associated with the AP
     * @param anqpRequestAllowed Indicates if to allow ANQP request if the provider's entry is empty
     * @return a list of pairs of {@link PasspointProvider} and match status.
     */
    private @NonNull List<Pair<PasspointProvider, PasspointMatch>> getAllMatchedProviders(
            ScanResult scanResult, boolean anqpRequestAllowed) {
        List<Pair<PasspointProvider, PasspointMatch>> allMatches = new ArrayList<>();

        // Retrieve the relevant information elements, mainly Roaming Consortium IE and Hotspot 2.0
        // Vendor Specific IE.
        InformationElementUtil.RoamingConsortium roamingConsortium =
                InformationElementUtil.getRoamingConsortiumIE(scanResult.informationElements);
        InformationElementUtil.Vsa vsa = InformationElementUtil.getHS2VendorSpecificIE(
                scanResult.informationElements);

        // Lookup ANQP data in the cache.
        long bssid;
        try {
            bssid = Utils.parseMac(scanResult.BSSID);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid BSSID provided in the scan result: " + scanResult.BSSID);
            return allMatches;
        }
        ANQPNetworkKey anqpKey = ANQPNetworkKey.buildKey(scanResult.SSID, bssid, scanResult.hessid,
                vsa.anqpDomainID);
        ANQPData anqpEntry = mAnqpCache.getEntry(anqpKey);
        if (anqpEntry == null) {
            if (anqpRequestAllowed) {
                mAnqpRequestManager.requestANQPElements(bssid, anqpKey,
                        roamingConsortium.anqpOICount > 0, vsa.hsRelease);
            }
            Log.d(TAG, "ANQP entry not found for: " + anqpKey);
            return allMatches;
        }
        boolean anyProviderUpdated = false;
        for (Map.Entry<String, PasspointProvider> entry : mProviders.entrySet()) {
            PasspointProvider provider = entry.getValue();
            if (provider.tryUpdateCarrierId()) {
                anyProviderUpdated = true;
            }
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Matching provider " + provider.getConfig().getHomeSp().getFqdn()
                        + " with "
                        + anqpEntry.getElements().get(Constants.ANQPElementType.ANQPDomName));
            }
            PasspointMatch matchStatus = provider.match(anqpEntry.getElements(),
                    roamingConsortium);
            if (matchStatus == PasspointMatch.HomeProvider
                    || matchStatus == PasspointMatch.RoamingProvider) {
                allMatches.add(Pair.create(provider, matchStatus));
            }
        }
        if (anyProviderUpdated) {
            mWifiConfigManager.saveToStore(true);
        }
        if (allMatches.size() != 0) {
            for (Pair<PasspointProvider, PasspointMatch> match : allMatches) {
                Log.d(TAG, String.format("Matched %s to %s as %s", scanResult.SSID,
                        match.first.getConfig().getHomeSp().getFqdn(),
                        match.second == PasspointMatch.HomeProvider ? "Home Provider"
                                : "Roaming Provider"));
            }
        } else {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "No service providers found for " + scanResult.SSID);
            }
        }
        return allMatches;
    }

    /**
     * Add a legacy Passpoint configuration represented by a {@link WifiConfiguration} to the
     * current {@link PasspointManager}.
     *
     * This will not trigger a config store write, since this will be invoked as part of the
     * configuration migration, the caller will be responsible for triggering store write
     * after the migration is completed.
     *
     * @param config {@link WifiConfiguration} representation of the Passpoint configuration
     * @return true on success
     */
    public static boolean addLegacyPasspointConfig(WifiConfiguration config) {
        if (sPasspointManager == null) {
            Log.e(TAG, "PasspointManager have not been initialized yet");
            return false;
        }
        Log.d(TAG, "Installing legacy Passpoint configuration: " + config.FQDN);
        return sPasspointManager.addWifiConfig(config);
    }

    /**
     * Sweep the ANQP cache to remove expired entries.
     */
    public void sweepCache() {
        mAnqpCache.sweep();
    }

    /**
     * Notify the completion of an ANQP request.
     * TODO(zqiu): currently the notification is done through WifiMonitor,
     * will no longer be the case once we switch over to use wificond.
     */
    public void notifyANQPDone(AnqpEvent anqpEvent) {
        mPasspointEventHandler.notifyANQPDone(anqpEvent);
    }

    /**
     * Notify the completion of an icon request.
     * TODO(zqiu): currently the notification is done through WifiMonitor,
     * will no longer be the case once we switch over to use wificond.
     */
    public void notifyIconDone(IconEvent iconEvent) {
        mPasspointEventHandler.notifyIconDone(iconEvent);
    }

    /**
     * Notify the reception of a Wireless Network Management (WNM) frame.
     * TODO(zqiu): currently the notification is done through WifiMonitor,
     * will no longer be the case once we switch over to use wificond.
     */
    public void receivedWnmFrame(WnmData data) {
        mPasspointEventHandler.notifyWnmFrameReceived(data);
    }

    /**
     * Request the specified icon file |fileName| from the specified AP |bssid|.
     * @return true if the request is sent successfully, false otherwise
     */
    public boolean queryPasspointIcon(long bssid, String fileName) {
        return mPasspointEventHandler.requestIcon(bssid, fileName);
    }

    /**
     * Lookup the ANQP elements associated with the given AP from the cache. An empty map
     * will be returned if no match found in the cache.
     *
     * @param scanResult The scan result associated with the AP
     * @return Map of ANQP elements
     */
    public Map<Constants.ANQPElementType, ANQPElement> getANQPElements(ScanResult scanResult) {
        // Retrieve the Hotspot 2.0 Vendor Specific IE.
        InformationElementUtil.Vsa vsa =
                InformationElementUtil.getHS2VendorSpecificIE(scanResult.informationElements);

        // Lookup ANQP data in the cache.
        long bssid;
        try {
            bssid = Utils.parseMac(scanResult.BSSID);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid BSSID provided in the scan result: " + scanResult.BSSID);
            return new HashMap<>();
        }
        ANQPData anqpEntry = mAnqpCache.getEntry(ANQPNetworkKey.buildKey(
                scanResult.SSID, bssid, scanResult.hessid, vsa.anqpDomainID));
        if (anqpEntry != null) {
            return anqpEntry.getElements();
        }
        return new HashMap<>();
    }

    /**
     * Return a map of all matching configurations keys with corresponding scanResults (or an empty
     * map if none).
     *
     * @param scanResults The list of scan results
     * @return Map that consists of identifies and corresponding scanResults per network type
     * ({@link WifiManager#PASSPOINT_HOME_NETWORK}, {@link WifiManager#PASSPOINT_ROAMING_NETWORK}).
     */
    public Map<String, Map<Integer, List<ScanResult>>>
            getAllMatchingPasspointProfilesForScanResults(List<ScanResult> scanResults) {
        if (scanResults == null) {
            Log.e(TAG, "Attempt to get matching config for a null ScanResults");
            return new HashMap<>();
        }
        Map<String, Map<Integer, List<ScanResult>>> configs = new HashMap<>();

        for (ScanResult scanResult : scanResults) {
            if (!scanResult.isPasspointNetwork()) continue;
            List<Pair<PasspointProvider, PasspointMatch>> matchedProviders = getAllMatchedProviders(
                    scanResult);
            for (Pair<PasspointProvider, PasspointMatch> matchedProvider : matchedProviders) {
                WifiConfiguration config = matchedProvider.first.getWifiConfig();
                int type = WifiManager.PASSPOINT_HOME_NETWORK;
                if (!config.isHomeProviderNetwork) {
                    type = WifiManager.PASSPOINT_ROAMING_NETWORK;
                }
                Map<Integer, List<ScanResult>> scanResultsPerNetworkType =
                        configs.get(config.getKey());
                if (scanResultsPerNetworkType == null) {
                    scanResultsPerNetworkType = new HashMap<>();
                    configs.put(config.getKey(), scanResultsPerNetworkType);
                }
                List<ScanResult> matchingScanResults = scanResultsPerNetworkType.get(type);
                if (matchingScanResults == null) {
                    matchingScanResults = new ArrayList<>();
                    scanResultsPerNetworkType.put(type, matchingScanResults);
                }
                matchingScanResults.add(scanResult);
            }
        }

        return configs;
    }

    /**
     * Returns the list of Hotspot 2.0 OSU (Online Sign-Up) providers associated with the given list
     * of ScanResult.
     *
     * An empty map will be returned when an invalid scanResults are provided or no match is found.
     *
     * @param scanResults a list of ScanResult that has Passpoint APs.
     * @return Map that consists of {@link OsuProvider} and a matching list of {@link ScanResult}
     */
    public Map<OsuProvider, List<ScanResult>> getMatchingOsuProviders(
            List<ScanResult> scanResults) {
        if (scanResults == null) {
            Log.e(TAG, "Attempt to retrieve OSU providers for a null ScanResult");
            return new HashMap();
        }

        Map<OsuProvider, List<ScanResult>> osuProviders = new HashMap<>();
        for (ScanResult scanResult : scanResults) {
            if (!scanResult.isPasspointNetwork()) continue;

            // Lookup OSU Providers ANQP element.
            Map<Constants.ANQPElementType, ANQPElement> anqpElements = getANQPElements(scanResult);
            if (!anqpElements.containsKey(Constants.ANQPElementType.HSOSUProviders)) {
                continue;
            }
            HSOsuProvidersElement element =
                    (HSOsuProvidersElement) anqpElements.get(
                            Constants.ANQPElementType.HSOSUProviders);
            for (OsuProviderInfo info : element.getProviders()) {
                // Set null for OSU-SSID in the class because OSU-SSID is a factor for hotspot
                // operator rather than service provider, which means it can be different for
                // each hotspot operators.
                OsuProvider provider = new OsuProvider((WifiSsid) null, info.getFriendlyNames(),
                        info.getServiceDescription(), info.getServerUri(),
                        info.getNetworkAccessIdentifier(), info.getMethodList());
                List<ScanResult> matchingScanResults = osuProviders.get(provider);
                if (matchingScanResults == null) {
                    matchingScanResults = new ArrayList<>();
                    osuProviders.put(provider, matchingScanResults);
                }
                matchingScanResults.add(scanResult);
            }
        }
        return osuProviders;
    }

    /**
     * Returns the matching Passpoint configurations for given OSU(Online Sign-Up) providers
     *
     * An empty map will be returned when an invalid {@code osuProviders} are provided or no match
     * is found.
     *
     * @param osuProviders a list of {@link OsuProvider}
     * @return Map that consists of {@link OsuProvider} and matching {@link PasspointConfiguration}.
     */
    public Map<OsuProvider, PasspointConfiguration> getMatchingPasspointConfigsForOsuProviders(
            List<OsuProvider> osuProviders) {
        Map<OsuProvider, PasspointConfiguration> matchingPasspointConfigs = new HashMap<>();

        for (OsuProvider osuProvider : osuProviders) {
            Map<String, String> friendlyNamesForOsuProvider = osuProvider.getFriendlyNameList();
            if (friendlyNamesForOsuProvider == null) continue;
            for (PasspointProvider provider : mProviders.values()) {
                PasspointConfiguration passpointConfiguration = provider.getConfig();
                Map<String, String> serviceFriendlyNamesForPpsMo =
                        passpointConfiguration.getServiceFriendlyNames();
                if (serviceFriendlyNamesForPpsMo == null) continue;

                for (Map.Entry<String, String> entry : serviceFriendlyNamesForPpsMo.entrySet()) {
                    String lang = entry.getKey();
                    String friendlyName = entry.getValue();
                    if (friendlyName == null) continue;
                    String osuFriendlyName = friendlyNamesForOsuProvider.get(lang);
                    if (osuFriendlyName == null) continue;
                    if (friendlyName.equals(osuFriendlyName)) {
                        matchingPasspointConfigs.put(osuProvider, passpointConfiguration);
                        break;
                    }
                }
            }
        }
        return matchingPasspointConfigs;
    }

    /**
     * Returns the corresponding wifi configurations for given a list Passpoint profile unique
     * identifiers.
     *
     * An empty list will be returned when no match is found.
     *
     * @param idList a list of unique identifiers
     * @return List of {@link WifiConfiguration} converted from {@link PasspointProvider}
     */
    public List<WifiConfiguration> getWifiConfigsForPasspointProfiles(List<String> idList) {
        if (mProviders.isEmpty()) {
            return Collections.emptyList();
        }
        List<WifiConfiguration> configs = new ArrayList<>();
        Set<String> uniqueIdSet = new HashSet<>();
        uniqueIdSet.addAll(idList);
        for (String uniqueId : uniqueIdSet) {
            PasspointProvider provider = mProviders.get(uniqueId);
            if (provider == null) {
                continue;
            }
            WifiConfiguration config = provider.getWifiConfig();
            // If the Passpoint configuration is from a suggestion, check if the app shares this
            // suggestion with the user.
            if (provider.isFromSuggestion()
                    && !mWifiInjector.getWifiNetworkSuggestionsManager()
                    .isPasspointSuggestionSharedWithUser(config)) {
                continue;
            }
            configs.add(config);
        }
        return configs;
    }

    /**
     * Invoked when a Passpoint network was successfully connected based on the credentials
     * provided by the given Passpoint provider
     *
     * @param uniqueId The unique identifier of the Passpointprofile
     */
    public void onPasspointNetworkConnected(String uniqueId) {
        PasspointProvider provider = mProviders.get(uniqueId);
        if (provider == null) {
            Log.e(TAG, "Passpoint network connected without provider: " + uniqueId);
            return;
        }
        if (!provider.getHasEverConnected()) {
            // First successful connection using this provider.
            provider.setHasEverConnected(true);
        }
    }

    /**
     * Update metrics related to installed Passpoint providers, this includes the number of
     * installed providers and the number of those providers that results in a successful network
     * connection.
     */
    public void updateMetrics() {
        int numProviders = mProviders.size();
        int numConnectedProviders = 0;
        for (Map.Entry<String, PasspointProvider> entry : mProviders.entrySet()) {
            if (entry.getValue().getHasEverConnected()) {
                numConnectedProviders++;
            }
        }
        mWifiMetrics.updateSavedPasspointProfilesInfo(mProviders);
        mWifiMetrics.updateSavedPasspointProfiles(numProviders, numConnectedProviders);
    }

    /**
     * Dump the current state of PasspointManager to the provided output stream.
     *
     * @param pw The output stream to write to
     */
    public void dump(PrintWriter pw) {
        pw.println("Dump of PasspointManager");
        pw.println("PasspointManager - Providers Begin ---");
        for (Map.Entry<String, PasspointProvider> entry : mProviders.entrySet()) {
            pw.println(entry.getValue());
        }
        pw.println("PasspointManager - Providers End ---");
        pw.println("PasspointManager - Next provider ID to be assigned " + mProviderIndex);
        mAnqpCache.dump(pw);
        mAnqpRequestManager.dump(pw);
    }

    /**
     * Add a legacy Passpoint configuration represented by a {@link WifiConfiguration}.
     *
     * @param wifiConfig {@link WifiConfiguration} representation of the Passpoint configuration
     * @return true on success
     */
    private boolean addWifiConfig(WifiConfiguration wifiConfig) {
        if (wifiConfig == null) {
            return false;
        }

        // Convert to PasspointConfiguration
        PasspointConfiguration passpointConfig =
                PasspointProvider.convertFromWifiConfig(wifiConfig);
        if (passpointConfig == null) {
            return false;
        }

        // Setup aliases for enterprise certificates and key.
        WifiEnterpriseConfig enterpriseConfig = wifiConfig.enterpriseConfig;
        String caCertificateAliasSuffix = enterpriseConfig.getCaCertificateAlias();
        String clientCertAndKeyAliasSuffix = enterpriseConfig.getClientCertificateAlias();
        if (passpointConfig.getCredential().getUserCredential() != null
                && TextUtils.isEmpty(caCertificateAliasSuffix)) {
            Log.e(TAG, "Missing CA Certificate for user credential");
            return false;
        }
        if (passpointConfig.getCredential().getCertCredential() != null) {
            if (TextUtils.isEmpty(caCertificateAliasSuffix)) {
                Log.e(TAG, "Missing CA certificate for Certificate credential");
                return false;
            }
            if (TextUtils.isEmpty(clientCertAndKeyAliasSuffix)) {
                Log.e(TAG, "Missing client certificate and key for certificate credential");
                return false;
            }
        }

        // Note that for legacy configuration, the alias for client private key is the same as the
        // alias for the client certificate.
        PasspointProvider provider = new PasspointProvider(passpointConfig, mKeyStore,
                mWifiCarrierInfoManager,
                mProviderIndex++, wifiConfig.creatorUid, null, false,
                Arrays.asList(enterpriseConfig.getCaCertificateAlias()),
                enterpriseConfig.getClientCertificateAlias(), null, false, false);
        provider.enableVerboseLogging(mVerboseLoggingEnabled ? 1 : 0);
        mProviders.put(passpointConfig.getUniqueId(), provider);
        return true;
    }

    /**
     * Start the subscription provisioning flow with a provider.
     * @param callingUid integer indicating the uid of the caller
     * @param provider {@link OsuProvider} the provider to subscribe to
     * @param callback {@link IProvisioningCallback} callback to update status to the caller
     * @return boolean return value from the provisioning method
     */
    public boolean startSubscriptionProvisioning(int callingUid, OsuProvider provider,
            IProvisioningCallback callback) {
        return mPasspointProvisioner.startSubscriptionProvisioning(callingUid, provider, callback);
    }

    /**
     * Check if a Passpoint configuration is expired
     *
     * @param config {@link PasspointConfiguration} Passpoint configuration
     * @return True if the configuration is expired, false if not or expiration is unset
     */
    private boolean isExpired(@NonNull PasspointConfiguration config) {
        long expirationTime = config.getSubscriptionExpirationTimeMillis();

        if (expirationTime != Long.MIN_VALUE) {
            long curTime = System.currentTimeMillis();

            // Check expiration and return true for expired profiles
            if (curTime >= expirationTime) {
                Log.d(TAG, "Profile for " + config.getServiceFriendlyName() + " has expired, "
                        + "expiration time: " + expirationTime + ", current time: "
                        + curTime);
                return true;
            }
        }
        return false;
    }

    /**
     * Get the filtered ScanResults which could be served by the {@link PasspointConfiguration}.
     * @param passpointConfiguration The instance of {@link PasspointConfiguration}
     * @param scanResults The list of {@link ScanResult}
     * @return The filtered ScanResults
     */
    @NonNull
    public List<ScanResult> getMatchingScanResults(
            @NonNull PasspointConfiguration passpointConfiguration,
            @NonNull List<ScanResult> scanResults) {
        PasspointProvider provider = mObjectFactory.makePasspointProvider(passpointConfiguration,
                null, mWifiCarrierInfoManager, 0, 0, null, false);
        List<ScanResult> filteredScanResults = new ArrayList<>();
        for (ScanResult scanResult : scanResults) {
            PasspointMatch matchInfo = provider.match(getANQPElements(scanResult),
                    InformationElementUtil.getRoamingConsortiumIE(scanResult.informationElements));
            if (matchInfo == PasspointMatch.HomeProvider
                    || matchInfo == PasspointMatch.RoamingProvider) {
                filteredScanResults.add(scanResult);
            }
        }

        return filteredScanResults;
    }

    /**
     * Check if the providers list is empty
     *
     * @return true if the providers list is empty, false otherwise
     */
    public boolean isProvidersListEmpty() {
        return mProviders.isEmpty();
    }

    /**
     * Clear ANQP requests and flush ANQP Cache (for factory reset)
     */
    public void clearAnqpRequestsAndFlushCache() {
        mAnqpRequestManager.clear();
        mAnqpCache.flush();
    }

    /**
     * Verify that the given certificate is trusted by one of the pre-loaded public CAs in the
     * system key store.
     *
     * @param caCert The CA Certificate to verify
     * @throws CertPathValidatorException
     * @throws Exception
     */
    private void verifyCaCert(X509Certificate caCert)
            throws GeneralSecurityException, IOException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        CertPathValidator validator =
                CertPathValidator.getInstance(CertPathValidator.getDefaultType());
        CertPath path = factory.generateCertPath(Arrays.asList(caCert));
        KeyStore ks = KeyStore.getInstance("AndroidCAStore");
        ks.load(null, null);
        PKIXParameters params = new PKIXParameters(ks);
        params.setRevocationEnabled(false);
        validator.validate(path, params);
    }
}
