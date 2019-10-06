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

import static com.android.server.wifi.hotspot2.Utils.isCarrierEapMethod;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiManager;
import android.net.wifi.hotspot2.IProvisioningCallback;
import android.net.wifi.hotspot2.OsuProvider;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import com.android.server.wifi.Clock;
import com.android.server.wifi.IMSIParameter;
import com.android.server.wifi.SIMAccessor;
import com.android.server.wifi.ScanDetail;
import com.android.server.wifi.WifiConfigManager;
import com.android.server.wifi.WifiConfigStore;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.WifiKeyStore;
import com.android.server.wifi.WifiMetrics;
import com.android.server.wifi.WifiNative;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.Constants;
import com.android.server.wifi.hotspot2.anqp.HSOsuProvidersElement;
import com.android.server.wifi.hotspot2.anqp.NAIRealmElement;
import com.android.server.wifi.hotspot2.anqp.OsuProviderInfo;
import com.android.server.wifi.util.InformationElementUtil;
import com.android.server.wifi.util.TelephonyUtil;

import java.io.PrintWriter;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
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
 * NOTE: These API's are not thread safe and should only be used from ClientModeImpl thread.
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
    private final SIMAccessor mSimAccessor;
    private final WifiKeyStore mKeyStore;
    private final PasspointObjectFactory mObjectFactory;

    private final Map<String, PasspointProvider> mProviders;
    private final AnqpCache mAnqpCache;
    private final ANQPRequestManager mAnqpRequestManager;
    private final WifiConfigManager mWifiConfigManager;
    private final CertificateVerifier mCertVerifier;
    private final WifiMetrics mWifiMetrics;
    private final PasspointProvisioner mPasspointProvisioner;
    private final TelephonyManager mTelephonyManager;
    private final AppOpsManager mAppOps;
    private final SubscriptionManager mSubscriptionManager;

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
                mProviders.put(provider.getConfig().getHomeSp().getFqdn(), provider);
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
            String fqdn = entry.getValue().getConfig().getHomeSp().getFqdn();
            removeProvider(fqdn);
            disconnectIfPasspointNetwork(fqdn);
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
            Log.wtf(TAG, "No app ops listener found for " + packageName);
            return;
        }
        mAppOps.stopWatchingMode(appOpsChangedListener);
    }

    private void disconnectIfPasspointNetwork(String fqdn) {
        WifiConfiguration currentConfiguration =
                mWifiInjector.getClientModeImpl().getCurrentWifiConfiguration();
        if (currentConfiguration == null) return;
        if (currentConfiguration.isPasspoint() && TextUtils.equals(currentConfiguration.FQDN,
                fqdn)) {
            Log.i(TAG, "Disconnect current Passpoint network for " + fqdn
                    + "because the profile was removed");
            mWifiInjector.getClientModeImpl().disconnectCommand();
        }
    }

    public PasspointManager(Context context, WifiInjector wifiInjector, Handler handler,
            WifiNative wifiNative, WifiKeyStore keyStore, Clock clock, SIMAccessor simAccessor,
            PasspointObjectFactory objectFactory, WifiConfigManager wifiConfigManager,
            WifiConfigStore wifiConfigStore,
            WifiMetrics wifiMetrics,
            TelephonyManager telephonyManager, SubscriptionManager subscriptionManager) {
        mPasspointEventHandler = objectFactory.makePasspointEventHandler(wifiNative,
                new CallbackHandler(context));
        mWifiInjector = wifiInjector;
        mHandler = handler;
        mKeyStore = keyStore;
        mSimAccessor = simAccessor;
        mObjectFactory = objectFactory;
        mProviders = new HashMap<>();
        mAnqpCache = objectFactory.makeAnqpCache(clock);
        mAnqpRequestManager = objectFactory.makeANQPRequestManager(mPasspointEventHandler, clock);
        mCertVerifier = objectFactory.makeCertificateVerifier();
        mWifiConfigManager = wifiConfigManager;
        mWifiMetrics = wifiMetrics;
        mProviderIndex = 0;
        mTelephonyManager = telephonyManager;
        mSubscriptionManager = subscriptionManager;
        wifiConfigStore.registerStoreData(objectFactory.makePasspointConfigUserStoreData(
                mKeyStore, mSimAccessor, new UserDataSourceHandler()));
        wifiConfigStore.registerStoreData(objectFactory.makePasspointConfigSharedStoreData(
                new SharedDataSourceHandler()));
        mPasspointProvisioner = objectFactory.makePasspointProvisioner(context, wifiNative,
                this, wifiMetrics);
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        sPasspointManager = this;
    }

    /**
     * Initializes the provisioning flow with a looper
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
    }

    /**
     * Add or update a Passpoint provider with the given configuration.
     *
     * Each provider is uniquely identified by its FQDN (Fully Qualified Domain Name).
     * In the case when there is an existing configuration with the same FQDN
     * a provider with the new configuration will replace the existing provider.
     *
     * @param config Configuration of the Passpoint provider to be added
     * @param packageName Package name of the app adding/Updating {@code config}
     * @return true if provider is added, false otherwise
     */
    public boolean addOrUpdateProvider(PasspointConfiguration config, int uid, String packageName) {
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
        X509Certificate[] x509Certificates = config.getCredential().getCaCertificates();
        if (config.getUpdateIdentifier() == Integer.MIN_VALUE && x509Certificates != null) {
            try {
                for (X509Certificate certificate : x509Certificates) {
                    mCertVerifier.verifyCaCert(certificate);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to verify CA certificate: " + e.getMessage());
                return false;
            }
        }

        // Create a provider and install the necessary certificates and keys.
        PasspointProvider newProvider = mObjectFactory.makePasspointProvider(
                config, mKeyStore, mSimAccessor, mProviderIndex++, uid, packageName);

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
        if (newProvider.getPackageName() != null) {
            startTrackingAppOpsChange(newProvider.getPackageName(), uid);
        }
        Log.d(TAG, "Added/updated Passpoint configuration: " + config.getHomeSp().getFqdn()
                + " by " + uid);
        mWifiMetrics.incrementNumPasspointProviderInstallSuccess();
        return true;
    }

    /**
     * Finds a EAP method from a NAI realm element matched with MCC/MNC of current carrier.
     *
     * @param scanDetails a list of scanResults used to find a matching AP.
     * @return a EAP method which should be one of EAP-Methods(EAP-SIM,AKA and AKA') if matching
     * realm is found, {@code -1} otherwise.
     */
    public int findEapMethodFromNAIRealmMatchedWithCarrier(List<ScanDetail> scanDetails) {
        if (!TelephonyUtil.isSimPresent(mSubscriptionManager)) {
            return -1;
        }
        if (scanDetails == null || scanDetails.isEmpty()) {
            return -1;
        }

        String mccMnc = mTelephonyManager
                .createForSubscriptionId(SubscriptionManager.getDefaultDataSubscriptionId())
                .getSimOperator();
        if (mccMnc == null || mccMnc.length() < IMSIParameter.MCC_MNC_LENGTH - 1) {
            return -1;
        }

        String domain = Utils.getRealmForMccMnc(mccMnc);
        if (domain == null) {
            return -1;
        }
        for (ScanDetail scanDetail : scanDetails) {
            if (!scanDetail.getNetworkDetail().isInterworking()) {
                // Skip non-Passpoint APs.
                continue;
            }

            // Lookup ANQP data in the cache.
            long bssid;
            ScanResult scanResult = scanDetail.getScanResult();
            InformationElementUtil.RoamingConsortium roamingConsortium =
                    InformationElementUtil.getRoamingConsortiumIE(scanResult.informationElements);
            InformationElementUtil.Vsa vsa = InformationElementUtil.getHS2VendorSpecificIE(
                    scanResult.informationElements);
            try {
                bssid = Utils.parseMac(scanResult.BSSID);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Invalid BSSID provided in the scan result: " + scanResult.BSSID);
                continue;
            }
            ANQPNetworkKey anqpKey = ANQPNetworkKey.buildKey(scanResult.SSID, bssid,
                    scanResult.hessid,
                    vsa.anqpDomainID);
            ANQPData anqpEntry = mAnqpCache.getEntry(anqpKey);

            if (anqpEntry == null) {
                mAnqpRequestManager.requestANQPElements(bssid, anqpKey,
                        roamingConsortium.anqpOICount > 0,
                        vsa.hsRelease == NetworkDetail.HSRelease.R2);
                Log.d(TAG, "ANQP entry not found for: " + anqpKey);
                continue;
            }

            // Find a matching domain that has following EAP methods(SIM/AKA/AKA') in NAI realms.
            NAIRealmElement naiRealmElement = (NAIRealmElement) anqpEntry.getElements().get(
                    Constants.ANQPElementType.ANQPNAIRealm);
            int eapMethod = ANQPMatcher.getCarrierEapMethodFromMatchingNAIRealm(domain,
                    naiRealmElement);
            if (eapMethod != -1) {
                return eapMethod;
            }
        }
        return -1;
    }

    /**
     * Creates an ephemeral {@link PasspointConfiguration} for current carrier(SIM) on the device.
     *
     * @param eapMethod  eapMethod used to connect Passpoint Network.
     * @return return the {@link PasspointConfiguration} if a configuration is created successfully,
     * {@code null} otherwise.
     */
    public PasspointConfiguration createEphemeralPasspointConfigForCarrier(int eapMethod) {
        String mccMnc = mTelephonyManager
                .createForSubscriptionId(SubscriptionManager.getDefaultDataSubscriptionId())
                .getSimOperator();
        if (mccMnc == null || mccMnc.length() < IMSIParameter.MCC_MNC_LENGTH - 1) {
            Log.e(TAG, "invalid length of mccmnc");
            return null;
        }

        if (!isCarrierEapMethod(eapMethod)) {
            Log.e(TAG, "invalid eapMethod type");
            return null;
        }

        String domain = Utils.getRealmForMccMnc(mccMnc);
        if (domain == null) {
            Log.e(TAG, "can't make a home domain name using " + mccMnc);
            return null;
        }
        PasspointConfiguration config = new PasspointConfiguration();
        HomeSp homeSp = new HomeSp();
        homeSp.setFqdn(domain);
        String friendlyName = mTelephonyManager
                .createForSubscriptionId(SubscriptionManager.getDefaultDataSubscriptionId())
                .getSimOperatorName();
        homeSp.setFriendlyName(friendlyName);
        config.setHomeSp(homeSp);

        Credential credential = new Credential();
        credential.setRealm(domain);
        Credential.SimCredential simCredential = new Credential.SimCredential();

        // prefix match
        simCredential.setImsi(mccMnc + "*");
        simCredential.setEapType(eapMethod);
        credential.setSimCredential(simCredential);
        config.setCredential(credential);
        if (!config.validate()) {
            Log.e(TAG, "Transient PasspointConfiguration is not a valid format: " + config);
            return null;
        }
        return config;
    }

    /**
     * Check if the {@link PasspointProvider} for a carrier exists.
     * @param mccmnc a MCC/MNC of the carrier to find
     * @return {@code true} if the provider already exists, {@code false} otherwise.
     */
    public boolean hasCarrierProvider(@Nullable String mccmnc) {
        String domain = Utils.getRealmForMccMnc(mccmnc);
        if (domain == null) {
            Log.e(TAG, "can't make a home domain name using " + mccmnc);
            return false;
        }

        // Check if we already have this provider
        for (Map.Entry<String, PasspointProvider> provider : mProviders.entrySet()) {
            PasspointConfiguration installedConfig = provider.getValue().getConfig();
            if (installedConfig.getCredential().getSimCredential() == null) {
                continue;
            }
            if (domain.equals(provider.getKey())) {
                // We already have the provider that has same FQDN.
                return true;
            }

            IMSIParameter imsiParameter = provider.getValue().getImsiParameter();
            if (imsiParameter == null) {
                continue;
            }

            if (imsiParameter.matchesMccMnc(mccmnc)) {
                // We already have the provider that has same IMSI.
                return true;
            }
        }
        return false;
    }

    /**
     * Installs a {@link PasspointConfiguration} created for auto connection with EAP-SIM/AKA/AKA'.
     *
     * It installs the Passpoint configuration created on runtime when the (MCC/MNC) of carrier that
     * supports encrypted IMSI is matched with one of ScanResults
     *
     * @param config the Passpoint Configuration to connect the AP with EAP-SIM/AKA/AKA'
     * @return {@code true} if config is installed successfully, {@code false} otherwise.
     */
    public boolean installEphemeralPasspointConfigForCarrier(PasspointConfiguration config) {
        if (config == null) {
            Log.e(TAG, "PasspointConfiguration for carrier is null");
            return false;
        }
        if (!TelephonyUtil.isSimPresent(mSubscriptionManager)) {
            Log.e(TAG, "Sim is not presented on the device");
            return false;
        }
        Credential.SimCredential simCredential = config.getCredential().getSimCredential();
        if (simCredential == null || simCredential.getImsi() == null) {
            Log.e(TAG, "This is not for a carrier configuration using EAP-SIM/AKA/AKA'");
            return false;
        }
        if (!config.validate()) {
            Log.e(TAG,
                    "It is not a valid format for Passpoint Configuration with EAP-SIM/AKA/AKA'");
            return false;
        }
        String imsi = simCredential.getImsi();
        if (imsi.length() < IMSIParameter.MCC_MNC_LENGTH) {
            Log.e(TAG, "Invalid IMSI length: " + imsi.length());
            return false;
        }
        int index = imsi.indexOf("*");
        if (index == -1) {
            Log.e(TAG, "missing * in imsi");
            return false;
        }
        if (hasCarrierProvider(imsi.substring(0, index))) {
            Log.e(TAG, "It is already in the Provider list");
            return false;
        }

        // Create a provider and install the necessary certificates and keys.
        PasspointProvider newProvider = mObjectFactory.makePasspointProvider(
                config, mKeyStore, mSimAccessor, mProviderIndex++, Process.WIFI_UID, null);
        newProvider.setEphemeral(true);
        Log.d(TAG, "installed PasspointConfiguration for carrier : "
                + config.getHomeSp().getFriendlyName());
        mProviders.put(config.getHomeSp().getFqdn(), newProvider);
        mWifiConfigManager.saveToStore(true /* forceWrite */);
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
        String packageName;
        if (!mProviders.containsKey(fqdn)) {
            Log.e(TAG, "Config doesn't exist");
            return false;
        }
        mProviders.get(fqdn).uninstallCertsAndKeys();
        packageName = mProviders.get(fqdn).getPackageName();
        mProviders.remove(fqdn);
        mWifiConfigManager.saveToStore(true /* forceWrite */);

        // Stop monitoring the package if there is no Passpoint profile installed by the package.
        if (mAppOpsChangedListenerPerApp.containsKey(packageName)
                && getPasspointProviderWithPackage(packageName).size() == 0) {
            stopTrackingAppOpsChange(packageName);
        }
        Log.d(TAG, "Removed Passpoint configuration: " + fqdn);
        mWifiMetrics.incrementNumPasspointProviderUninstallSuccess();
        return true;
    }

    /**
     * Remove the ephemeral providers that are created temporarily for a carrier.
     */
    public void removeEphemeralProviders() {
        mProviders.entrySet().removeIf(entry -> {
            PasspointProvider provider = entry.getValue();
            if (provider != null && provider.isEphemeral()) {
                mWifiConfigManager.removePasspointConfiguredNetwork(entry.getKey());
                return true;
            }
            return false;
        });
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
        List<Pair<PasspointProvider, PasspointMatch>> allMatches = getAllMatchedProviders(
                scanResult);
        if (allMatches == null) {
            return null;
        }
        Pair<PasspointProvider, PasspointMatch> bestMatch = null;
        for (Pair<PasspointProvider, PasspointMatch> match : allMatches) {
            if (match.second == PasspointMatch.HomeProvider) {
                bestMatch = match;
                break;
            }
            if (match.second == PasspointMatch.RoamingProvider && bestMatch == null) {
                bestMatch = match;
            }
        }
        if (bestMatch != null) {
            Log.d(TAG, String.format("Matched %s to %s as %s", scanResult.SSID,
                    bestMatch.first.getConfig().getHomeSp().getFqdn(),
                    bestMatch.second == PasspointMatch.HomeProvider ? "Home Provider"
                            : "Roaming Provider"));
        } else {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "No service provider found for " + scanResult.SSID);
            }
        }
        return bestMatch;
    }

    /**
     * Return a list of all providers that can provide service through the given AP.
     *
     * @param scanResult The scan result associated with the AP
     * @return a list of pairs of {@link PasspointProvider} and match status.
     */
    public List<Pair<PasspointProvider, PasspointMatch>> getAllMatchedProviders(
            ScanResult scanResult) {
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
            mAnqpRequestManager.requestANQPElements(bssid, anqpKey,
                    roamingConsortium.anqpOICount > 0,
                    vsa.hsRelease  == NetworkDetail.HSRelease.R2);
            Log.d(TAG, "ANQP entry not found for: " + anqpKey);
            return allMatches;
        }
        for (Map.Entry<String, PasspointProvider> entry : mProviders.entrySet()) {
            PasspointProvider provider = entry.getValue();
            PasspointMatch matchStatus = provider.match(anqpEntry.getElements(),
                    roamingConsortium);
            if (matchStatus == PasspointMatch.HomeProvider
                    || matchStatus == PasspointMatch.RoamingProvider) {
                allMatches.add(Pair.create(provider, matchStatus));
            }
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
     * Returns a list of FQDN (Fully Qualified Domain Name) for installed Passpoint configurations.
     *
     * Return the map of all matching configurations with corresponding scanResults (or an empty
     * map if none).
     *
     * @param scanResults The list of scan results
     * @return Map that consists of FQDN (Fully Qualified Domain Name) and corresponding
     * scanResults per network type({@link WifiManager#PASSPOINT_HOME_NETWORK} and {@link
     * WifiManager#PASSPOINT_ROAMING_NETWORK}).
     */
    public Map<String, Map<Integer, List<ScanResult>>> getAllMatchingFqdnsForScanResults(
            List<ScanResult> scanResults) {
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
                Map<Integer, List<ScanResult>> scanResultsPerNetworkType = configs.get(config.FQDN);
                if (scanResultsPerNetworkType == null) {
                    scanResultsPerNetworkType = new HashMap<>();
                    configs.put(config.FQDN, scanResultsPerNetworkType);
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
                OsuProvider provider = new OsuProvider(null, info.getFriendlyNames(),
                        info.getServiceDescription(), info.getServerUri(),
                        info.getNetworkAccessIdentifier(), info.getMethodList(), null);
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
        List<PasspointConfiguration> passpointConfigurations = getProviderConfigs();

        for (OsuProvider osuProvider : osuProviders) {
            Map<String, String> friendlyNamesForOsuProvider = osuProvider.getFriendlyNameList();
            if (friendlyNamesForOsuProvider == null) continue;
            for (PasspointConfiguration passpointConfiguration : passpointConfigurations) {
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
     * Returns the corresponding wifi configurations for given FQDN (Fully Qualified Domain Name)
     * list.
     *
     * An empty list will be returned when no match is found.
     *
     * @param fqdnList a list of FQDN
     * @return List of {@link WifiConfiguration} converted from {@link PasspointProvider}
     */
    public List<WifiConfiguration> getWifiConfigsForPasspointProfiles(List<String> fqdnList) {
        Set<String> fqdnSet = new HashSet<>();
        fqdnSet.addAll(fqdnList);
        List<WifiConfiguration> configs = new ArrayList<>();
        for (String fqdn : fqdnSet) {
            PasspointProvider provider = mProviders.get(fqdn);
            if (provider != null) {
                configs.add(provider.getWifiConfig());
            }
        }
        return configs;
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
                mSimAccessor, mProviderIndex++, wifiConfig.creatorUid, null,
                Arrays.asList(enterpriseConfig.getCaCertificateAlias()),
                enterpriseConfig.getClientCertificateAlias(),
                enterpriseConfig.getClientCertificateAlias(), null, false, false);
        mProviders.put(passpointConfig.getHomeSp().getFqdn(), provider);
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
}
