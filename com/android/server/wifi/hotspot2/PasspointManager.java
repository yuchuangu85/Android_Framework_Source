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

import static android.net.wifi.WifiManager.ACTION_PASSPOINT_DEAUTH_IMMINENT;
import static android.net.wifi.WifiManager.ACTION_PASSPOINT_ICON;
import static android.net.wifi.WifiManager.ACTION_PASSPOINT_SUBSCRIPTION_REMEDIATION;
import static android.net.wifi.WifiManager.EXTRA_BSSID_LONG;
import static android.net.wifi.WifiManager.EXTRA_DELAY;
import static android.net.wifi.WifiManager.EXTRA_ESS;
import static android.net.wifi.WifiManager.EXTRA_FILENAME;
import static android.net.wifi.WifiManager.EXTRA_ICON;
import static android.net.wifi.WifiManager.EXTRA_SUBSCRIPTION_REMEDIATION_METHOD;
import static android.net.wifi.WifiManager.EXTRA_URL;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.server.wifi.Clock;
import com.android.server.wifi.SIMAccessor;
import com.android.server.wifi.WifiConfigManager;
import com.android.server.wifi.WifiConfigStore;
import com.android.server.wifi.WifiKeyStore;
import com.android.server.wifi.WifiMetrics;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants;
import com.android.server.wifi.hotspot2.anqp.HSOsuProvidersElement;
import com.android.server.wifi.hotspot2.anqp.OsuProviderInfo;
import com.android.server.wifi.util.InformationElementUtil;
import com.android.server.wifi.util.ScanResultUtil;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * NOTE: These API's are not thread safe and should only be used from WifiStateMachine thread.
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

    private final PasspointEventHandler mHandler;
    private final SIMAccessor mSimAccessor;
    private final WifiKeyStore mKeyStore;
    private final PasspointObjectFactory mObjectFactory;
    private final Map<String, PasspointProvider> mProviders;
    private final AnqpCache mAnqpCache;
    private final ANQPRequestManager mAnqpRequestManager;
    private final WifiConfigManager mWifiConfigManager;
    private final CertificateVerifier mCertVerifier;
    private final WifiMetrics mWifiMetrics;

    // Counter used for assigning unique identifier to each provider.
    private long mProviderIndex;

    private class CallbackHandler implements PasspointEventHandler.Callbacks {
        private final Context mContext;
        CallbackHandler(Context context) {
            mContext = context;
        }

        @Override
        public void onANQPResponse(long bssid,
                Map<Constants.ANQPElementType, ANQPElement> anqpElements) {
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
            Intent intent = new Intent(ACTION_PASSPOINT_ICON);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
            intent.putExtra(EXTRA_BSSID_LONG, bssid);
            intent.putExtra(EXTRA_FILENAME, fileName);
            if (data != null) {
                intent.putExtra(EXTRA_ICON, Icon.createWithData(data, 0, data.length));
            }
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                    android.Manifest.permission.ACCESS_WIFI_STATE);
        }

        @Override
        public void onWnmFrameReceived(WnmData event) {
            // %012x HS20-SUBSCRIPTION-REMEDIATION "%u %s", osu_method, url
            // %012x HS20-DEAUTH-IMMINENT-NOTICE "%u %u %s", code, reauth_delay, url
            Intent intent;
            if (event.isDeauthEvent()) {
                intent = new Intent(ACTION_PASSPOINT_DEAUTH_IMMINENT);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(EXTRA_BSSID_LONG, event.getBssid());
                intent.putExtra(EXTRA_URL, event.getUrl());
                intent.putExtra(EXTRA_ESS, event.isEss());
                intent.putExtra(EXTRA_DELAY, event.getDelay());
            } else {
                intent = new Intent(ACTION_PASSPOINT_SUBSCRIPTION_REMEDIATION);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                intent.putExtra(EXTRA_BSSID_LONG, event.getBssid());
                intent.putExtra(EXTRA_SUBSCRIPTION_REMEDIATION_METHOD, event.getMethod());
                intent.putExtra(EXTRA_URL, event.getUrl());
            }
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                    android.Manifest.permission.ACCESS_WIFI_STATE);
        }
    }

    /**
     * Data provider for the Passpoint configuration store data {@link PasspointConfigStoreData}.
     */
    private class DataSourceHandler implements PasspointConfigStoreData.DataSource {
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
                mProviders.put(provider.getConfig().getHomeSp().getFqdn(), provider);
            }
        }

        @Override
        public long getProviderIndex() {
            return mProviderIndex;
        }

        @Override
        public void setProviderIndex(long providerIndex) {
            mProviderIndex = providerIndex;
        }
    }

    public PasspointManager(Context context, WifiNative wifiNative, WifiKeyStore keyStore,
            Clock clock, SIMAccessor simAccessor, PasspointObjectFactory objectFactory,
            WifiConfigManager wifiConfigManager, WifiConfigStore wifiConfigStore,
            WifiMetrics wifiMetrics) {
        mHandler = objectFactory.makePasspointEventHandler(wifiNative,
                new CallbackHandler(context));
        mKeyStore = keyStore;
        mSimAccessor = simAccessor;
        mObjectFactory = objectFactory;
        mProviders = new HashMap<>();
        mAnqpCache = objectFactory.makeAnqpCache(clock);
        mAnqpRequestManager = objectFactory.makeANQPRequestManager(mHandler, clock);
        mCertVerifier = objectFactory.makeCertificateVerifier();
        mWifiConfigManager = wifiConfigManager;
        mWifiMetrics = wifiMetrics;
        mProviderIndex = 0;
        wifiConfigStore.registerStoreData(objectFactory.makePasspointConfigStoreData(
                mKeyStore, mSimAccessor, new DataSourceHandler()));
        sPasspointManager = this;
    }

    /**
     * Add or update a Passpoint provider with the given configuration.
     *
     * Each provider is uniquely identified by its FQDN (Fully Qualified Domain Name).
     * In the case when there is an existing configuration with the same FQDN
     * a provider with the new configuration will replace the existing provider.
     *
     * @param config Configuration of the Passpoint provider to be added
     * @return true if provider is added, false otherwise
     */
    public boolean addOrUpdateProvider(PasspointConfiguration config, int uid) {
        mWifiMetrics.incrementNumPasspointProviderInstallation();
        if (config == null) {
            Log.e(TAG, "Configuration not provided");
            return false;
        }
        if (!config.validate()) {
            Log.e(TAG, "Invalid configuration");
            return false;
        }

        // For Hotspot 2.0 Release 1, the CA Certificate must be trusted by one of the pre-loaded
        // public CAs in the system key store on the device.  Since the provisioning method
        // for Release 1 is not standardized nor trusted,  this is a reasonable restriction
        // to improve security.  The presence of UpdateIdentifier is used to differentiate
        // between R1 and R2 configuration.
        if (config.getUpdateIdentifier() == Integer.MIN_VALUE
                && config.getCredential().getCaCertificate() != null) {
            try {
                mCertVerifier.verifyCaCert(config.getCredential().getCaCertificate());
            } catch (Exception e) {
                Log.e(TAG, "Failed to verify CA certificate: " + e.getMessage());
                return false;
            }
        }

        // Create a provider and install the necessary certificates and keys.
        PasspointProvider newProvider = mObjectFactory.makePasspointProvider(
                config, mKeyStore, mSimAccessor, mProviderIndex++, uid);

        if (!newProvider.installCertsAndKeys()) {
            Log.e(TAG, "Failed to install certificates and keys to keystore");
            return false;
        }

        // Remove existing provider with the same FQDN.
        if (mProviders.containsKey(config.getHomeSp().getFqdn())) {
            Log.d(TAG, "Replacing configuration for " + config.getHomeSp().getFqdn());
            mProviders.get(config.getHomeSp().getFqdn()).uninstallCertsAndKeys();
            mProviders.remove(config.getHomeSp().getFqdn());
        }

        mProviders.put(config.getHomeSp().getFqdn(), newProvider);
        mWifiConfigManager.saveToStore(true /* forceWrite */);
        Log.d(TAG, "Added/updated Passpoint configuration: " + config.getHomeSp().getFqdn()
                + " by " + uid);
        mWifiMetrics.incrementNumPasspointProviderInstallSuccess();
        return true;
    }

    /**
     * Remove a Passpoint provider identified by the given FQDN.
     *
     * @param fqdn The FQDN of the provider to remove
     * @return true if a provider is removed, false otherwise
     */
    public boolean removeProvider(String fqdn) {
        mWifiMetrics.incrementNumPasspointProviderUninstallation();
        if (!mProviders.containsKey(fqdn)) {
            Log.e(TAG, "Config doesn't exist");
            return false;
        }

        mProviders.get(fqdn).uninstallCertsAndKeys();
        mProviders.remove(fqdn);
        mWifiConfigManager.saveToStore(true /* forceWrite */);
        Log.d(TAG, "Removed Passpoint configuration: " + fqdn);
        mWifiMetrics.incrementNumPasspointProviderUninstallSuccess();
        return true;
    }

    /**
     * Return the installed Passpoint provider configurations.
     *
     * An empty list will be returned when no provider is installed.
     *
     * @return A list of {@link PasspointConfiguration}
     */
    public List<PasspointConfiguration> getProviderConfigs() {
        List<PasspointConfiguration> configs = new ArrayList<>();
        for (Map.Entry<String, PasspointProvider> entry : mProviders.entrySet()) {
            configs.add(entry.getValue().getConfig());
        }
        return configs;
    }

    /**
     * Find the best provider that can provide service through the given AP, which means the
     * provider contained credential to authenticate with the given AP.
     *
     * Here is the current precedence of the matching rule in descending order:
     * 1. Home Provider
     * 2. Roaming Provider
     *
     * A {code null} will be returned if no matching is found.
     *
     * @param scanResult The scan result associated with the AP
     * @return A pair of {@link PasspointProvider} and match status.
     */
    public Pair<PasspointProvider, PasspointMatch> matchProvider(ScanResult scanResult) {
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
            return null;
        }
        ANQPNetworkKey anqpKey = ANQPNetworkKey.buildKey(scanResult.SSID, bssid, scanResult.hessid,
                vsa.anqpDomainID);
        ANQPData anqpEntry = mAnqpCache.getEntry(anqpKey);

        if (anqpEntry == null) {
            mAnqpRequestManager.requestANQPElements(bssid, anqpKey,
                    roamingConsortium.anqpOICount > 0,
                    vsa.hsRelease  == NetworkDetail.HSRelease.R2);
            Log.d(TAG, "ANQP entry not found for: " + anqpKey);
            return null;
        }

        Pair<PasspointProvider, PasspointMatch> bestMatch = null;
        for (Map.Entry<String, PasspointProvider> entry : mProviders.entrySet()) {
            PasspointProvider provider = entry.getValue();
            PasspointMatch matchStatus = provider.match(anqpEntry.getElements());
            if (matchStatus == PasspointMatch.HomeProvider) {
                bestMatch = Pair.create(provider, matchStatus);
                break;
            }
            if (matchStatus == PasspointMatch.RoamingProvider && bestMatch == null) {
                bestMatch = Pair.create(provider, matchStatus);
            }
        }
        if (bestMatch != null) {
            Log.d(TAG, String.format("Matched %s to %s as %s", scanResult.SSID,
                    bestMatch.first.getConfig().getHomeSp().getFqdn(),
                    bestMatch.second == PasspointMatch.HomeProvider ? "Home Provider"
                            : "Roaming Provider"));
        } else {
            Log.d(TAG, "Match not found for " + scanResult.SSID);
        }
        return bestMatch;
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
        mHandler.notifyANQPDone(anqpEvent);
    }

    /**
     * Notify the completion of an icon request.
     * TODO(zqiu): currently the notification is done through WifiMonitor,
     * will no longer be the case once we switch over to use wificond.
     */
    public void notifyIconDone(IconEvent iconEvent) {
        mHandler.notifyIconDone(iconEvent);
    }

    /**
     * Notify the reception of a Wireless Network Management (WNM) frame.
     * TODO(zqiu): currently the notification is done through WifiMonitor,
     * will no longer be the case once we switch over to use wificond.
     */
    public void receivedWnmFrame(WnmData data) {
        mHandler.notifyWnmFrameReceived(data);
    }

    /**
     * Request the specified icon file |fileName| from the specified AP |bssid|.
     * @return true if the request is sent successfully, false otherwise
     */
    public boolean queryPasspointIcon(long bssid, String fileName) {
        return mHandler.requestIcon(bssid, fileName);
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
            return new HashMap<Constants.ANQPElementType, ANQPElement>();
        }
        ANQPData anqpEntry = mAnqpCache.getEntry(ANQPNetworkKey.buildKey(
                scanResult.SSID, bssid, scanResult.hessid, vsa.anqpDomainID));
        if (anqpEntry != null) {
            return anqpEntry.getElements();
        }
        return new HashMap<Constants.ANQPElementType, ANQPElement>();
    }

    /**
     * Match the given WiFi AP to an installed Passpoint provider.  A {@link WifiConfiguration}
     * will be generated and returned if a match is found.  The returned {@link WifiConfiguration}
     * will contained all the necessary credentials for connecting to the given WiFi AP.
     *
     * A {code null} will be returned if no matching provider is found.
     *
     * @param scanResult The scan result of the given AP
     * @return {@link WifiConfiguration}
     */
    public WifiConfiguration getMatchingWifiConfig(ScanResult scanResult) {
        if (scanResult == null) {
            Log.e(TAG, "Attempt to get matching config for a null ScanResult");
            return null;
        }
        if (!scanResult.isPasspointNetwork()) {
            Log.e(TAG, "Attempt to get matching config for a non-Passpoint AP");
            return null;
        }
        Pair<PasspointProvider, PasspointMatch> matchedProvider = matchProvider(scanResult);
        if (matchedProvider == null) {
            return null;
        }
        WifiConfiguration config = matchedProvider.first.getWifiConfig();
        config.SSID = ScanResultUtil.createQuotedSSID(scanResult.SSID);
        if (matchedProvider.second == PasspointMatch.HomeProvider) {
            config.isHomeProviderNetwork = true;
        }
        return config;
    }

    /**
     * Return the list of Hosspot 2.0 OSU (Online Sign-Up) providers associated with the given
     * AP.
     *
     * An empty list will be returned when an invalid scan result is provided or no match is found.
     *
     * @param scanResult The scan result of the AP
     * @return List of {@link OsuProvider}
     */
    public List<OsuProvider> getMatchingOsuProviders(ScanResult scanResult) {
        if (scanResult == null) {
            Log.e(TAG, "Attempt to retrieve OSU providers for a null ScanResult");
            return new ArrayList<OsuProvider>();
        }
        if (!scanResult.isPasspointNetwork()) {
            Log.e(TAG, "Attempt to retrieve OSU providers for a non-Passpoint AP");
            return new ArrayList<OsuProvider>();
        }

        // Lookup OSU Providers ANQP element.
        Map<Constants.ANQPElementType, ANQPElement> anqpElements = getANQPElements(scanResult);
        if (!anqpElements.containsKey(Constants.ANQPElementType.HSOSUProviders)) {
            return new ArrayList<OsuProvider>();
        }

        HSOsuProvidersElement element =
                (HSOsuProvidersElement) anqpElements.get(Constants.ANQPElementType.HSOSUProviders);
        List<OsuProvider> providers = new ArrayList<>();
        for (OsuProviderInfo info : element.getProviders()) {
            // TODO(b/62256482): include icon data once the icon file retrieval and management
            // support is added.
            OsuProvider provider = new OsuProvider(element.getOsuSsid(), info.getFriendlyName(),
                    info.getServiceDescription(), info.getServerUri(),
                    info.getNetworkAccessIdentifier(), info.getMethodList(), null);
            providers.add(provider);
        }
        return providers;
    }

    /**
     * Invoked when a Passpoint network was successfully connected based on the credentials
     * provided by the given Passpoint provider (specified by its FQDN).
     *
     * @param fqdn The FQDN of the Passpoint provider
     */
    public void onPasspointNetworkConnected(String fqdn) {
        PasspointProvider provider = mProviders.get(fqdn);
        if (provider == null) {
            Log.e(TAG, "Passpoint network connected without provider: " + fqdn);
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
                mSimAccessor, mProviderIndex++, wifiConfig.creatorUid,
                enterpriseConfig.getCaCertificateAlias(),
                enterpriseConfig.getClientCertificateAlias(),
                enterpriseConfig.getClientCertificateAlias(), false);
        mProviders.put(passpointConfig.getHomeSp().getFqdn(), provider);
        return true;
    }
}
