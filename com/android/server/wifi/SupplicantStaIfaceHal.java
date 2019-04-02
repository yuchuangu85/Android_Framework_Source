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
package com.android.server.wifi;

import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.ANQP3GPPNetwork;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.ANQPDomName;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.ANQPIPAddrAvailability;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.ANQPNAIRealm;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.ANQPRoamingConsortium;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.ANQPVenueName;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.HSConnCapability;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.HSFriendlyName;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.HSOSUProviders;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.HSWANMetrics;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.wifi.supplicant.V1_0.ISupplicant;
import android.hardware.wifi.supplicant.V1_0.ISupplicantIface;
import android.hardware.wifi.supplicant.V1_0.ISupplicantNetwork;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIface;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIfaceCallback;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIfaceCallback.BssidChangeReason;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaNetwork;
import android.hardware.wifi.supplicant.V1_0.IfaceType;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatus;
import android.hardware.wifi.supplicant.V1_0.SupplicantStatusCode;
import android.hardware.wifi.supplicant.V1_0.WpsConfigMethods;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.net.IpConfiguration;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.os.HwRemoteBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.server.wifi.hotspot2.AnqpEvent;
import com.android.server.wifi.hotspot2.IconEvent;
import com.android.server.wifi.hotspot2.WnmData;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.ANQPParser;
import com.android.server.wifi.hotspot2.anqp.Constants;
import com.android.server.wifi.util.NativeUtil;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Hal calls for bring up/shut down of the supplicant daemon and for
 * sending requests to the supplicant daemon
 * To maintain thread-safety, the locking protocol is that every non-static method (regardless of
 * access level) acquires mLock.
 */
@ThreadSafe
public class SupplicantStaIfaceHal {
    private static final String TAG = "SupplicantStaIfaceHal";
    /**
     * Regex pattern for extracting the wps device type bytes.
     * Matches a strings like the following: "<categ>-<OUI>-<subcateg>";
     */
    private static final Pattern WPS_DEVICE_TYPE_PATTERN =
            Pattern.compile("^(\\d{1,2})-([0-9a-fA-F]{8})-(\\d{1,2})$");

    private final Object mLock = new Object();
    private boolean mVerboseLoggingEnabled = false;

    // Supplicant HAL interface objects
    private IServiceManager mIServiceManager = null;
    private ISupplicant mISupplicant;
    private ISupplicantStaIface mISupplicantStaIface;
    private ISupplicantStaIfaceCallback mISupplicantStaIfaceCallback;
    private final IServiceNotification mServiceNotificationCallback =
            new IServiceNotification.Stub() {
        public void onRegistration(String fqName, String name, boolean preexisting) {
            synchronized (mLock) {
                if (mVerboseLoggingEnabled) {
                    Log.i(TAG, "IServiceNotification.onRegistration for: " + fqName
                            + ", " + name + " preexisting=" + preexisting);
                }
                if (!initSupplicantService() || !initSupplicantStaIface()) {
                    Log.e(TAG, "initalizing ISupplicantIfaces failed.");
                    supplicantServiceDiedHandler();
                } else {
                    Log.i(TAG, "Completed initialization of ISupplicant interfaces.");
                }
            }
        }
    };
    private final HwRemoteBinder.DeathRecipient mServiceManagerDeathRecipient =
            cookie -> {
                synchronized (mLock) {
                    Log.w(TAG, "IServiceManager died: cookie=" + cookie);
                    supplicantServiceDiedHandler();
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                }
            };
    private final HwRemoteBinder.DeathRecipient mSupplicantDeathRecipient =
            cookie -> {
                synchronized (mLock) {
                    Log.w(TAG, "ISupplicant/ISupplicantStaIface died: cookie=" + cookie);
                    supplicantServiceDiedHandler();
                }
            };

    private String mIfaceName;
    private SupplicantStaNetworkHal mCurrentNetworkRemoteHandle;
    private WifiConfiguration mCurrentNetworkLocalConfig;
    private final Context mContext;
    private final WifiMonitor mWifiMonitor;

    public SupplicantStaIfaceHal(Context context, WifiMonitor monitor) {
        mContext = context;
        mWifiMonitor = monitor;
        mISupplicantStaIfaceCallback = new SupplicantStaIfaceHalCallback();
    }

    /**
     * Enable/Disable verbose logging.
     *
     * @param enable true to enable, false to disable.
     */
    void enableVerboseLogging(boolean enable) {
        synchronized (mLock) {
            mVerboseLoggingEnabled = enable;
        }
    }

    private boolean linkToServiceManagerDeath() {
        synchronized (mLock) {
            if (mIServiceManager == null) return false;
            try {
                if (!mIServiceManager.linkToDeath(mServiceManagerDeathRecipient, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on IServiceManager");
                    supplicantServiceDiedHandler();
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "IServiceManager.linkToDeath exception", e);
                return false;
            }
            return true;
        }
    }

    /**
     * Registers a service notification for the ISupplicant service, which triggers intialization of
     * the ISupplicantStaIface
     * @return true if the service notification was successfully registered
     */
    public boolean initialize() {
        synchronized (mLock) {
            if (mVerboseLoggingEnabled) {
                Log.i(TAG, "Registering ISupplicant service ready callback.");
            }
            mISupplicant = null;
            mISupplicantStaIface = null;
            if (mIServiceManager != null) {
                // Already have an IServiceManager and serviceNotification registered, don't
                // don't register another.
                return true;
            }
            try {
                mIServiceManager = getServiceManagerMockable();
                if (mIServiceManager == null) {
                    Log.e(TAG, "Failed to get HIDL Service Manager");
                    return false;
                }
                if (!linkToServiceManagerDeath()) {
                    return false;
                }
                /* TODO(b/33639391) : Use the new ISupplicant.registerForNotifications() once it
                   exists */
                if (!mIServiceManager.registerForNotifications(
                        ISupplicant.kInterfaceName, "", mServiceNotificationCallback)) {
                    Log.e(TAG, "Failed to register for notifications to "
                            + ISupplicant.kInterfaceName);
                    mIServiceManager = null; // Will need to register a new ServiceNotification
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception while trying to register a listener for ISupplicant service: "
                        + e);
                supplicantServiceDiedHandler();
            }
            return true;
        }
    }

    private boolean linkToSupplicantDeath() {
        synchronized (mLock) {
            if (mISupplicant == null) return false;
            try {
                if (!mISupplicant.linkToDeath(mSupplicantDeathRecipient, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on ISupplicant");
                    supplicantServiceDiedHandler();
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicant.linkToDeath exception", e);
                return false;
            }
            return true;
        }
    }

    private boolean initSupplicantService() {
        synchronized (mLock) {
            try {
                mISupplicant = getSupplicantMockable();
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicant.getService exception: " + e);
                return false;
            }
            if (mISupplicant == null) {
                Log.e(TAG, "Got null ISupplicant service. Stopping supplicant HIDL startup");
                return false;
            }
            if (!linkToSupplicantDeath()) {
                return false;
            }
        }
        return true;
    }

    private boolean linkToSupplicantStaIfaceDeath() {
        synchronized (mLock) {
            if (mISupplicantStaIface == null) return false;
            try {
                if (!mISupplicantStaIface.linkToDeath(mSupplicantDeathRecipient, 0)) {
                    Log.wtf(TAG, "Error on linkToDeath on ISupplicantStaIface");
                    supplicantServiceDiedHandler();
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicantStaIface.linkToDeath exception", e);
                return false;
            }
            return true;
        }
    }

    private int getCurrentNetworkId() {
        synchronized (mLock) {
            if (mCurrentNetworkLocalConfig == null) {
                return WifiConfiguration.INVALID_NETWORK_ID;
            }
            return mCurrentNetworkLocalConfig.networkId;
        }
    }

    private boolean initSupplicantStaIface() {
        synchronized (mLock) {
            /** List all supplicant Ifaces */
            final ArrayList<ISupplicant.IfaceInfo> supplicantIfaces = new ArrayList<>();
            try {
                mISupplicant.listInterfaces((SupplicantStatus status,
                        ArrayList<ISupplicant.IfaceInfo> ifaces) -> {
                    if (status.code != SupplicantStatusCode.SUCCESS) {
                        Log.e(TAG, "Getting Supplicant Interfaces failed: " + status.code);
                        return;
                    }
                    supplicantIfaces.addAll(ifaces);
                });
            } catch (RemoteException e) {
                Log.e(TAG, "ISupplicant.listInterfaces exception: " + e);
                return false;
            }
            if (supplicantIfaces.size() == 0) {
                Log.e(TAG, "Got zero HIDL supplicant ifaces. Stopping supplicant HIDL startup.");
                return false;
            }
            Mutable<ISupplicantIface> supplicantIface = new Mutable<>();
            Mutable<String> ifaceName = new Mutable<>();
            for (ISupplicant.IfaceInfo ifaceInfo : supplicantIfaces) {
                if (ifaceInfo.type == IfaceType.STA) {
                    try {
                        mISupplicant.getInterface(ifaceInfo,
                                (SupplicantStatus status, ISupplicantIface iface) -> {
                                if (status.code != SupplicantStatusCode.SUCCESS) {
                                    Log.e(TAG, "Failed to get ISupplicantIface " + status.code);
                                    return;
                                }
                                supplicantIface.value = iface;
                            });
                    } catch (RemoteException e) {
                        Log.e(TAG, "ISupplicant.getInterface exception: " + e);
                        return false;
                    }
                    ifaceName.value = ifaceInfo.name;
                    break;
                }
            }
            if (supplicantIface.value == null) {
                Log.e(TAG, "initSupplicantStaIface got null iface");
                return false;
            }
            mISupplicantStaIface = getStaIfaceMockable(supplicantIface.value);
            mIfaceName = ifaceName.value;
            if (!linkToSupplicantStaIfaceDeath()) {
                return false;
            }
            if (!registerCallback(mISupplicantStaIfaceCallback)) {
                return false;
            }
            return true;
        }
    }

    private void supplicantServiceDiedHandler() {
        synchronized (mLock) {
            mISupplicant = null;
            mISupplicantStaIface = null;
            mWifiMonitor.broadcastSupplicantDisconnectionEvent(mIfaceName);
        }
    }

    /**
     * Signals whether Initialization completed successfully.
     */
    public boolean isInitializationStarted() {
        synchronized (mLock) {
            return mIServiceManager != null;
        }
    }

    /**
     * Signals whether Initialization completed successfully.
     */
    public boolean isInitializationComplete() {
        synchronized (mLock) {
            return mISupplicantStaIface != null;
        }
    }

    /**
     * Wrapper functions to access static HAL methods, created to be mockable in unit tests
     */
    protected IServiceManager getServiceManagerMockable() throws RemoteException {
        synchronized (mLock) {
            return IServiceManager.getService();
        }
    }

    protected ISupplicant getSupplicantMockable() throws RemoteException {
        synchronized (mLock) {
            return ISupplicant.getService();
        }
    }

    protected ISupplicantStaIface getStaIfaceMockable(ISupplicantIface iface) {
        synchronized (mLock) {
            return ISupplicantStaIface.asInterface(iface.asBinder());
        }
    }

    /**
     * Add a network configuration to wpa_supplicant.
     *
     * @param config Config corresponding to the network.
     * @return a Pair object including SupplicantStaNetworkHal and WifiConfiguration objects
     * for the current network.
     */
    private Pair<SupplicantStaNetworkHal, WifiConfiguration>
            addNetworkAndSaveConfig(WifiConfiguration config) {
        synchronized (mLock) {
            logi("addSupplicantStaNetwork via HIDL");
            if (config == null) {
                loge("Cannot add NULL network!");
                return null;
            }
            SupplicantStaNetworkHal network = addNetwork();
            if (network == null) {
                loge("Failed to add a network!");
                return null;
            }
            boolean saveSuccess = false;
            try {
                saveSuccess = network.saveWifiConfiguration(config);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Exception while saving config params: " + config, e);
            }
            if (!saveSuccess) {
                loge("Failed to save variables for: " + config.configKey());
                if (!removeAllNetworks()) {
                    loge("Failed to remove all networks on failure.");
                }
                return null;
            }
            return new Pair(network, new WifiConfiguration(config));
        }
    }

    /**
     * Add the provided network configuration to wpa_supplicant and initiate connection to it.
     * This method does the following:
     * 1. If |config| is different to the current supplicant network, removes all supplicant
     * networks and saves |config|.
     * 2. Select the new network in wpa_supplicant.
     *
     * @param config WifiConfiguration parameters for the provided network.
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean connectToNetwork(@NonNull WifiConfiguration config) {
        synchronized (mLock) {
            logd("connectToNetwork " + config.configKey());
            if (WifiConfigurationUtil.isSameNetwork(config, mCurrentNetworkLocalConfig)) {
                logd("Network is already saved, will not trigger remove and add operation.");
            } else {
                mCurrentNetworkRemoteHandle = null;
                mCurrentNetworkLocalConfig = null;
                if (!removeAllNetworks()) {
                    loge("Failed to remove existing networks");
                    return false;
                }
                Pair<SupplicantStaNetworkHal, WifiConfiguration> pair =
                        addNetworkAndSaveConfig(config);
                if (pair == null) {
                    loge("Failed to add/save network configuration: " + config.configKey());
                    return false;
                }
                mCurrentNetworkRemoteHandle = pair.first;
                mCurrentNetworkLocalConfig = pair.second;
            }

            if (!mCurrentNetworkRemoteHandle.select()) {
                loge("Failed to select network configuration: " + config.configKey());
                return false;
            }
            return true;
        }
    }

    /**
     * Initiates roaming to the already configured network in wpa_supplicant. If the network
     * configuration provided does not match the already configured network, then this triggers
     * a new connection attempt (instead of roam).
     * 1. First check if we're attempting to connect to the same network as we currently have
     * configured.
     * 2. Set the new bssid for the network in wpa_supplicant.
     * 3. Trigger reassociate command to wpa_supplicant.
     *
     * @param config WifiConfiguration parameters for the provided network.
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean roamToNetwork(WifiConfiguration config) {
        synchronized (mLock) {
            if (getCurrentNetworkId() != config.networkId) {
                Log.w(TAG, "Cannot roam to a different network, initiate new connection. "
                        + "Current network ID: " + getCurrentNetworkId());
                return connectToNetwork(config);
            }
            String bssid = config.getNetworkSelectionStatus().getNetworkSelectionBSSID();
            logd("roamToNetwork" + config.configKey() + " (bssid " + bssid + ")");
            if (!mCurrentNetworkRemoteHandle.setBssid(bssid)) {
                loge("Failed to set new bssid on network: " + config.configKey());
                return false;
            }
            if (!reassociate()) {
                loge("Failed to trigger reassociate");
                return false;
            }
            return true;
        }
    }

    /**
     * Load all the configured networks from wpa_supplicant.
     *
     * @param configs       Map of configuration key to configuration objects corresponding to all
     *                      the networks.
     * @param networkExtras Map of extra configuration parameters stored in wpa_supplicant.conf
     * @return true if succeeds, false otherwise.
     */
    public boolean loadNetworks(Map<String, WifiConfiguration> configs,
                                SparseArray<Map<String, String>> networkExtras) {
        synchronized (mLock) {
            List<Integer> networkIds = listNetworks();
            if (networkIds == null) {
                Log.e(TAG, "Failed to list networks");
                return false;
            }
            for (Integer networkId : networkIds) {
                SupplicantStaNetworkHal network = getNetwork(networkId);
                if (network == null) {
                    Log.e(TAG, "Failed to get network with ID: " + networkId);
                    return false;
                }
                WifiConfiguration config = new WifiConfiguration();
                Map<String, String> networkExtra = new HashMap<>();
                boolean loadSuccess = false;
                try {
                    loadSuccess = network.loadWifiConfiguration(config, networkExtra);
                } catch (IllegalArgumentException e) {
                    Log.wtf(TAG, "Exception while loading config params: " + config, e);
                }
                if (!loadSuccess) {
                    Log.e(TAG, "Failed to load wifi configuration for network with ID: " + networkId
                            + ". Skipping...");
                    continue;
                }
                // Set the default IP assignments.
                config.setIpAssignment(IpConfiguration.IpAssignment.DHCP);
                config.setProxySettings(IpConfiguration.ProxySettings.NONE);

                networkExtras.put(networkId, networkExtra);
                String configKey =
                        networkExtra.get(SupplicantStaNetworkHal.ID_STRING_KEY_CONFIG_KEY);
                final WifiConfiguration duplicateConfig = configs.put(configKey, config);
                if (duplicateConfig != null) {
                    // The network is already known. Overwrite the duplicate entry.
                    Log.i(TAG, "Replacing duplicate network: " + duplicateConfig.networkId);
                    removeNetwork(duplicateConfig.networkId);
                    networkExtras.remove(duplicateConfig.networkId);
                }
            }
            return true;
        }
    }

    /**
     * Remove the request |networkId| from supplicant if it's the current network,
     * if the current configured network matches |networkId|.
     *
     * @param networkId network id of the network to be removed from supplicant.
     */
    public void removeNetworkIfCurrent(int networkId) {
        synchronized (mLock) {
            if (getCurrentNetworkId() == networkId) {
                // Currently we only save 1 network in supplicant.
                removeAllNetworks();
            }
        }
    }

    /**
     * Remove all networks from supplicant
     */
    public boolean removeAllNetworks() {
        synchronized (mLock) {
            ArrayList<Integer> networks = listNetworks();
            if (networks == null) {
                Log.e(TAG, "removeAllNetworks failed, got null networks");
                return false;
            }
            for (int id : networks) {
                if (!removeNetwork(id)) {
                    Log.e(TAG, "removeAllNetworks failed to remove network: " + id);
                    return false;
                }
            }
            // Reset current network info.  Probably not needed once we add support to remove/reset
            // current network on receiving disconnection event from supplicant (b/32898136).
            mCurrentNetworkLocalConfig = null;
            mCurrentNetworkRemoteHandle = null;
            return true;
        }
    }

    /**
     * Set the currently configured network's bssid.
     *
     * @param bssidStr Bssid to set in the form of "XX:XX:XX:XX:XX:XX"
     * @return true if succeeds, false otherwise.
     */
    public boolean setCurrentNetworkBssid(String bssidStr) {
        synchronized (mLock) {
            if (mCurrentNetworkRemoteHandle == null) return false;
            return mCurrentNetworkRemoteHandle.setBssid(bssidStr);
        }
    }

    /**
     * Get the currently configured network's WPS NFC token.
     *
     * @return Hex string corresponding to the WPS NFC token.
     */
    public String getCurrentNetworkWpsNfcConfigurationToken() {
        synchronized (mLock) {
            if (mCurrentNetworkRemoteHandle == null) return null;
            return mCurrentNetworkRemoteHandle.getWpsNfcConfigurationToken();
        }
    }

    /**
     * Get the eap anonymous identity for the currently configured network.
     *
     * @return anonymous identity string if succeeds, null otherwise.
     */
    public String getCurrentNetworkEapAnonymousIdentity() {
        synchronized (mLock) {
            if (mCurrentNetworkRemoteHandle == null) return null;
            return mCurrentNetworkRemoteHandle.fetchEapAnonymousIdentity();
        }
    }

    /**
     * Send the eap identity response for the currently configured network.
     *
     * @param identityStr String to send.
     * @return true if succeeds, false otherwise.
     */
    public boolean sendCurrentNetworkEapIdentityResponse(String identityStr) {
        synchronized (mLock) {
            if (mCurrentNetworkRemoteHandle == null) return false;
            return mCurrentNetworkRemoteHandle.sendNetworkEapIdentityResponse(identityStr);
        }
    }

    /**
     * Send the eap sim gsm auth response for the currently configured network.
     *
     * @param paramsStr String to send.
     * @return true if succeeds, false otherwise.
     */
    public boolean sendCurrentNetworkEapSimGsmAuthResponse(String paramsStr) {
        synchronized (mLock) {
            if (mCurrentNetworkRemoteHandle == null) return false;
            return mCurrentNetworkRemoteHandle.sendNetworkEapSimGsmAuthResponse(paramsStr);
        }
    }

    /**
     * Send the eap sim gsm auth failure for the currently configured network.
     *
     * @return true if succeeds, false otherwise.
     */
    public boolean sendCurrentNetworkEapSimGsmAuthFailure() {
        synchronized (mLock) {
            if (mCurrentNetworkRemoteHandle == null) return false;
            return mCurrentNetworkRemoteHandle.sendNetworkEapSimGsmAuthFailure();
        }
    }

    /**
     * Send the eap sim umts auth response for the currently configured network.
     *
     * @param paramsStr String to send.
     * @return true if succeeds, false otherwise.
     */
    public boolean sendCurrentNetworkEapSimUmtsAuthResponse(String paramsStr) {
        synchronized (mLock) {
            if (mCurrentNetworkRemoteHandle == null) return false;
            return mCurrentNetworkRemoteHandle.sendNetworkEapSimUmtsAuthResponse(paramsStr);
        }
    }

    /**
     * Send the eap sim umts auts response for the currently configured network.
     *
     * @param paramsStr String to send.
     * @return true if succeeds, false otherwise.
     */
    public boolean sendCurrentNetworkEapSimUmtsAutsResponse(String paramsStr) {
        synchronized (mLock) {
            if (mCurrentNetworkRemoteHandle == null) return false;
            return mCurrentNetworkRemoteHandle.sendNetworkEapSimUmtsAutsResponse(paramsStr);
        }
    }

    /**
     * Send the eap sim umts auth failure for the currently configured network.
     *
     * @return true if succeeds, false otherwise.
     */
    public boolean sendCurrentNetworkEapSimUmtsAuthFailure() {
        synchronized (mLock) {
            if (mCurrentNetworkRemoteHandle == null) return false;
            return mCurrentNetworkRemoteHandle.sendNetworkEapSimUmtsAuthFailure();
        }
    }

    /**
     * Adds a new network.
     *
     * @return The ISupplicantNetwork object for the new network, or null if the call fails
     */
    private SupplicantStaNetworkHal addNetwork() {
        synchronized (mLock) {
            final String methodStr = "addNetwork";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            Mutable<ISupplicantNetwork> newNetwork = new Mutable<>();
            try {
                mISupplicantStaIface.addNetwork((SupplicantStatus status,
                        ISupplicantNetwork network) -> {
                    if (checkStatusAndLogFailure(status, methodStr)) {
                        newNetwork.value = network;
                    }
                });
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            }
            if (newNetwork.value != null) {
                return getStaNetworkMockable(
                        ISupplicantStaNetwork.asInterface(newNetwork.value.asBinder()));
            } else {
                return null;
            }
        }
    }

    /**
     * Remove network from supplicant with network Id
     *
     * @return true if request is sent successfully, false otherwise.
     */
    private boolean removeNetwork(int id) {
        synchronized (mLock) {
            final String methodStr = "removeNetwork";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.removeNetwork(id);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Use this to mock the creation of SupplicantStaNetworkHal instance.
     *
     * @param iSupplicantStaNetwork ISupplicantStaNetwork instance retrieved from HIDL.
     * @return The ISupplicantNetwork object for the given SupplicantNetworkId int, returns null if
     * the call fails
     */
    protected SupplicantStaNetworkHal getStaNetworkMockable(
            ISupplicantStaNetwork iSupplicantStaNetwork) {
        synchronized (mLock) {
            SupplicantStaNetworkHal network =
                    new SupplicantStaNetworkHal(iSupplicantStaNetwork, mIfaceName, mContext,
                            mWifiMonitor);
            if (network != null) {
                network.enableVerboseLogging(mVerboseLoggingEnabled);
            }
            return network;
        }
    }

    /**
     * @return The ISupplicantNetwork object for the given SupplicantNetworkId int, returns null if
     * the call fails
     */
    private SupplicantStaNetworkHal getNetwork(int id) {
        synchronized (mLock) {
            final String methodStr = "getNetwork";
            Mutable<ISupplicantNetwork> gotNetwork = new Mutable<>();
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            try {
                mISupplicantStaIface.getNetwork(id, (SupplicantStatus status,
                        ISupplicantNetwork network) -> {
                    if (checkStatusAndLogFailure(status, methodStr)) {
                        gotNetwork.value = network;
                    }
                });
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            }
            if (gotNetwork.value != null) {
                return getStaNetworkMockable(
                        ISupplicantStaNetwork.asInterface(gotNetwork.value.asBinder()));
            } else {
                return null;
            }
        }
    }

    /** See ISupplicantStaNetwork.hal for documentation */
    private boolean registerCallback(ISupplicantStaIfaceCallback callback) {
        synchronized (mLock) {
            final String methodStr = "registerCallback";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =  mISupplicantStaIface.registerCallback(callback);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * @return a list of SupplicantNetworkID ints for all networks controlled by supplicant, returns
     * null if the call fails
     */
    private java.util.ArrayList<Integer> listNetworks() {
        synchronized (mLock) {
            final String methodStr = "listNetworks";
            Mutable<ArrayList<Integer>> networkIdList = new Mutable<>();
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            try {
                mISupplicantStaIface.listNetworks((SupplicantStatus status,
                        java.util.ArrayList<Integer> networkIds) -> {
                    if (checkStatusAndLogFailure(status, methodStr)) {
                        networkIdList.value = networkIds;
                    }
                });
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            }
            return networkIdList.value;
        }
    }

    /**
     * Set WPS device name.
     *
     * @param name String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setWpsDeviceName(String name) {
        synchronized (mLock) {
            final String methodStr = "setWpsDeviceName";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setWpsDeviceName(name);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Set WPS device type.
     *
     * @param typeStr Type specified as a string. Used format: <categ>-<OUI>-<subcateg>
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setWpsDeviceType(String typeStr) {
        synchronized (mLock) {
            try {
                Matcher match = WPS_DEVICE_TYPE_PATTERN.matcher(typeStr);
                if (!match.find() || match.groupCount() != 3) {
                    Log.e(TAG, "Malformed WPS device type " + typeStr);
                    return false;
                }
                short categ = Short.parseShort(match.group(1));
                byte[] oui = NativeUtil.hexStringToByteArray(match.group(2));
                short subCateg = Short.parseShort(match.group(3));

                byte[] bytes = new byte[8];
                ByteBuffer byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
                byteBuffer.putShort(categ);
                byteBuffer.put(oui);
                byteBuffer.putShort(subCateg);
                return setWpsDeviceType(bytes);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Illegal argument " + typeStr, e);
                return false;
            }
        }
    }

    private boolean setWpsDeviceType(byte[/* 8 */] type) {
        synchronized (mLock) {
            final String methodStr = "setWpsDeviceType";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setWpsDeviceType(type);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Set WPS manufacturer.
     *
     * @param manufacturer String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setWpsManufacturer(String manufacturer) {
        synchronized (mLock) {
            final String methodStr = "setWpsManufacturer";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setWpsManufacturer(manufacturer);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Set WPS model name.
     *
     * @param modelName String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setWpsModelName(String modelName) {
        synchronized (mLock) {
            final String methodStr = "setWpsModelName";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setWpsModelName(modelName);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Set WPS model number.
     *
     * @param modelNumber String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setWpsModelNumber(String modelNumber) {
        synchronized (mLock) {
            final String methodStr = "setWpsModelNumber";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setWpsModelNumber(modelNumber);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Set WPS serial number.
     *
     * @param serialNumber String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setWpsSerialNumber(String serialNumber) {
        synchronized (mLock) {
            final String methodStr = "setWpsSerialNumber";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setWpsSerialNumber(serialNumber);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Set WPS config methods
     *
     * @param configMethodsStr List of config methods.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setWpsConfigMethods(String configMethodsStr) {
        synchronized (mLock) {
            short configMethodsMask = 0;
            String[] configMethodsStrArr = configMethodsStr.split("\\s+");
            for (int i = 0; i < configMethodsStrArr.length; i++) {
                configMethodsMask |= stringToWpsConfigMethod(configMethodsStrArr[i]);
            }
            return setWpsConfigMethods(configMethodsMask);
        }
    }

    private boolean setWpsConfigMethods(short configMethods) {
        synchronized (mLock) {
            final String methodStr = "setWpsConfigMethods";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setWpsConfigMethods(configMethods);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Trigger a reassociation even if the iface is currently connected.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean reassociate() {
        synchronized (mLock) {
            final String methodStr = "reassociate";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.reassociate();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Trigger a reconnection if the iface is disconnected.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean reconnect() {
        synchronized (mLock) {
            final String methodStr = "reconnect";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.reconnect();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Trigger a disconnection from the currently connected network.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean disconnect() {
        synchronized (mLock) {
            final String methodStr = "disconnect";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.disconnect();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Enable or disable power save mode.
     *
     * @param enable true to enable, false to disable.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setPowerSave(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setPowerSave";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setPowerSave(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Initiate TDLS discover with the specified AP.
     *
     * @param macAddress MAC Address of the AP.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean initiateTdlsDiscover(String macAddress) {
        synchronized (mLock) {
            try {
                return initiateTdlsDiscover(NativeUtil.macAddressToByteArray(macAddress));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Illegal argument " + macAddress, e);
                return false;
            }
        }
    }
    /** See ISupplicantStaIface.hal for documentation */
    private boolean initiateTdlsDiscover(byte[/* 6 */] macAddress) {
        synchronized (mLock) {
            final String methodStr = "initiateTdlsDiscover";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateTdlsDiscover(macAddress);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Initiate TDLS setup with the specified AP.
     *
     * @param macAddress MAC Address of the AP.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean initiateTdlsSetup(String macAddress) {
        synchronized (mLock) {
            try {
                return initiateTdlsSetup(NativeUtil.macAddressToByteArray(macAddress));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Illegal argument " + macAddress, e);
                return false;
            }
        }
    }
    /** See ISupplicantStaIface.hal for documentation */
    private boolean initiateTdlsSetup(byte[/* 6 */] macAddress) {
        synchronized (mLock) {
            final String methodStr = "initiateTdlsSetup";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateTdlsSetup(macAddress);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Initiate TDLS teardown with the specified AP.
     * @param macAddress MAC Address of the AP.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean initiateTdlsTeardown(String macAddress) {
        synchronized (mLock) {
            try {
                return initiateTdlsTeardown(NativeUtil.macAddressToByteArray(macAddress));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Illegal argument " + macAddress, e);
                return false;
            }
        }
    }

    /** See ISupplicantStaIface.hal for documentation */
    private boolean initiateTdlsTeardown(byte[/* 6 */] macAddress) {
        synchronized (mLock) {
            final String methodStr = "initiateTdlsTeardown";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateTdlsTeardown(macAddress);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Request the specified ANQP elements |elements| from the specified AP |bssid|.
     *
     * @param bssid BSSID of the AP
     * @param infoElements ANQP elements to be queried. Refer to ISupplicantStaIface.AnqpInfoId.
     * @param hs20SubTypes HS subtypes to be queried. Refer to ISupplicantStaIface.Hs20AnqpSubTypes.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean initiateAnqpQuery(String bssid, ArrayList<Short> infoElements,
                                     ArrayList<Integer> hs20SubTypes) {
        synchronized (mLock) {
            try {
                return initiateAnqpQuery(
                        NativeUtil.macAddressToByteArray(bssid), infoElements, hs20SubTypes);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Illegal argument " + bssid, e);
                return false;
            }
        }
    }

    /** See ISupplicantStaIface.hal for documentation */
    private boolean initiateAnqpQuery(byte[/* 6 */] macAddress,
            java.util.ArrayList<Short> infoElements, java.util.ArrayList<Integer> subTypes) {
        synchronized (mLock) {
            final String methodStr = "initiateAnqpQuery";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateAnqpQuery(macAddress,
                        infoElements, subTypes);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Request the specified ANQP ICON from the specified AP |bssid|.
     *
     * @param bssid BSSID of the AP
     * @param fileName Name of the file to request.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean initiateHs20IconQuery(String bssid, String fileName) {
        synchronized (mLock) {
            try {
                return initiateHs20IconQuery(NativeUtil.macAddressToByteArray(bssid), fileName);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Illegal argument " + bssid, e);
                return false;
            }
        }
    }

    /** See ISupplicantStaIface.hal for documentation */
    private boolean initiateHs20IconQuery(byte[/* 6 */] macAddress, String fileName) {
        synchronized (mLock) {
            final String methodStr = "initiateHs20IconQuery";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.initiateHs20IconQuery(macAddress,
                        fileName);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Makes a callback to HIDL to getMacAddress from supplicant
     *
     * @return string containing the MAC address, or null on a failed call
     */
    public String getMacAddress() {
        synchronized (mLock) {
            final String methodStr = "getMacAddress";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            Mutable<String> gotMac = new Mutable<>();
            try {
                mISupplicantStaIface.getMacAddress((SupplicantStatus status,
                        byte[/* 6 */] macAddr) -> {
                    if (checkStatusAndLogFailure(status, methodStr)) {
                        gotMac.value = NativeUtil.macAddressFromByteArray(macAddr);
                    }
                });
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            }
            return gotMac.value;
        }
    }

    /**
     * Start using the added RX filters.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startRxFilter() {
        synchronized (mLock) {
            final String methodStr = "startRxFilter";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.startRxFilter();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Stop using the added RX filters.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean stopRxFilter() {
        synchronized (mLock) {
            final String methodStr = "stopRxFilter";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.stopRxFilter();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Add an RX filter.
     *
     * @param type one of {@link WifiNative#RX_FILTER_TYPE_V4_MULTICAST}
     *        {@link WifiNative#RX_FILTER_TYPE_V6_MULTICAST} values.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean addRxFilter(int type) {
        synchronized (mLock) {
            byte halType;
            switch (type) {
                case WifiNative.RX_FILTER_TYPE_V4_MULTICAST:
                    halType = ISupplicantStaIface.RxFilterType.V4_MULTICAST;
                    break;
                case WifiNative.RX_FILTER_TYPE_V6_MULTICAST:
                    halType = ISupplicantStaIface.RxFilterType.V6_MULTICAST;
                    break;
                default:
                    Log.e(TAG, "Invalid Rx Filter type: " + type);
                    return false;
            }
            return addRxFilter(halType);
        }
    }

    public boolean addRxFilter(byte type) {
        synchronized (mLock) {
            final String methodStr = "addRxFilter";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.addRxFilter(type);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Remove an RX filter.
     *
     * @param type one of {@link WifiNative#RX_FILTER_TYPE_V4_MULTICAST}
     *        {@link WifiNative#RX_FILTER_TYPE_V6_MULTICAST} values.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean removeRxFilter(int type) {
        synchronized (mLock) {
            byte halType;
            switch (type) {
                case WifiNative.RX_FILTER_TYPE_V4_MULTICAST:
                    halType = ISupplicantStaIface.RxFilterType.V4_MULTICAST;
                    break;
                case WifiNative.RX_FILTER_TYPE_V6_MULTICAST:
                    halType = ISupplicantStaIface.RxFilterType.V6_MULTICAST;
                    break;
                default:
                    Log.e(TAG, "Invalid Rx Filter type: " + type);
                    return false;
            }
            return removeRxFilter(halType);
        }
    }

    public boolean removeRxFilter(byte type) {
        synchronized (mLock) {
            final String methodStr = "removeRxFilter";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.removeRxFilter(type);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Set Bt co existense mode.
     *
     * @param mode one of the above {@link WifiNative#BLUETOOTH_COEXISTENCE_MODE_DISABLED},
     *             {@link WifiNative#BLUETOOTH_COEXISTENCE_MODE_ENABLED} or
     *             {@link WifiNative#BLUETOOTH_COEXISTENCE_MODE_SENSE}.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setBtCoexistenceMode(int mode) {
        synchronized (mLock) {
            byte halMode;
            switch (mode) {
                case WifiNative.BLUETOOTH_COEXISTENCE_MODE_ENABLED:
                    halMode = ISupplicantStaIface.BtCoexistenceMode.ENABLED;
                    break;
                case WifiNative.BLUETOOTH_COEXISTENCE_MODE_DISABLED:
                    halMode = ISupplicantStaIface.BtCoexistenceMode.DISABLED;
                    break;
                case WifiNative.BLUETOOTH_COEXISTENCE_MODE_SENSE:
                    halMode = ISupplicantStaIface.BtCoexistenceMode.SENSE;
                    break;
                default:
                    Log.e(TAG, "Invalid Bt Coex mode: " + mode);
                    return false;
            }
            return setBtCoexistenceMode(halMode);
        }
    }

    private boolean setBtCoexistenceMode(byte mode) {
        synchronized (mLock) {
            final String methodStr = "setBtCoexistenceMode";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setBtCoexistenceMode(mode);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /** Enable or disable BT coexistence mode.
     *
     * @param enable true to enable, false to disable.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setBtCoexistenceScanModeEnabled(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setBtCoexistenceScanModeEnabled";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =
                        mISupplicantStaIface.setBtCoexistenceScanModeEnabled(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Enable or disable suspend mode optimizations.
     *
     * @param enable true to enable, false otherwise.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setSuspendModeEnabled(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "setSuspendModeEnabled";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setSuspendModeEnabled(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Set country code.
     *
     * @param codeStr 2 byte ASCII string. For ex: US, CA.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setCountryCode(String codeStr) {
        synchronized (mLock) {
            if (TextUtils.isEmpty(codeStr)) return false;
            return setCountryCode(NativeUtil.stringToByteArray(codeStr));
        }
    }

    /** See ISupplicantStaIface.hal for documentation */
    private boolean setCountryCode(byte[/* 2 */] code) {
        synchronized (mLock) {
            final String methodStr = "setCountryCode";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setCountryCode(code);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Start WPS pin registrar operation with the specified peer and pin.
     *
     * @param bssidStr BSSID of the peer.
     * @param pin Pin to be used.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startWpsRegistrar(String bssidStr, String pin) {
        synchronized (mLock) {
            if (TextUtils.isEmpty(bssidStr) || TextUtils.isEmpty(pin)) return false;
            try {
                return startWpsRegistrar(NativeUtil.macAddressToByteArray(bssidStr), pin);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Illegal argument " + bssidStr, e);
                return false;
            }
        }
    }

    /** See ISupplicantStaIface.hal for documentation */
    private boolean startWpsRegistrar(byte[/* 6 */] bssid, String pin) {
        synchronized (mLock) {
            final String methodStr = "startWpsRegistrar";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.startWpsRegistrar(bssid, pin);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Start WPS pin display operation with the specified peer.
     *
     * @param bssidStr BSSID of the peer. Use empty bssid to indicate wildcard.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startWpsPbc(String bssidStr) {
        synchronized (mLock) {
            try {
                return startWpsPbc(NativeUtil.macAddressToByteArray(bssidStr));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Illegal argument " + bssidStr, e);
                return false;
            }
        }
    }

    /** See ISupplicantStaIface.hal for documentation */
    private boolean startWpsPbc(byte[/* 6 */] bssid) {
        synchronized (mLock) {
            final String methodStr = "startWpsPbc";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.startWpsPbc(bssid);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Start WPS pin keypad operation with the specified pin.
     *
     * @param pin Pin to be used.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startWpsPinKeypad(String pin) {
        if (TextUtils.isEmpty(pin)) return false;
        synchronized (mLock) {
            final String methodStr = "startWpsPinKeypad";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.startWpsPinKeypad(pin);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Start WPS pin display operation with the specified peer.
     *
     * @param bssidStr BSSID of the peer. Use empty bssid to indicate wildcard.
     * @return new pin generated on success, null otherwise.
     */
    public String startWpsPinDisplay(String bssidStr) {
        synchronized (mLock) {
            try {
                return startWpsPinDisplay(NativeUtil.macAddressToByteArray(bssidStr));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Illegal argument " + bssidStr, e);
                return null;
            }
        }
    }

    /** See ISupplicantStaIface.hal for documentation */
    private String startWpsPinDisplay(byte[/* 6 */] bssid) {
        synchronized (mLock) {
            final String methodStr = "startWpsPinDisplay";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return null;
            final Mutable<String> gotPin = new Mutable<>();
            try {
                mISupplicantStaIface.startWpsPinDisplay(bssid,
                        (SupplicantStatus status, String pin) -> {
                            if (checkStatusAndLogFailure(status, methodStr)) {
                                gotPin.value = pin;
                            }
                        });
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
            }
            return gotPin.value;
        }
    }

    /**
     * Cancels any ongoing WPS requests.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean cancelWps() {
        synchronized (mLock) {
            final String methodStr = "cancelWps";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.cancelWps();
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Sets whether to use external sim for SIM/USIM processing.
     *
     * @param useExternalSim true to enable, false otherwise.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setExternalSim(boolean useExternalSim) {
        synchronized (mLock) {
            final String methodStr = "setExternalSim";
            if (!checkSupplicantStaIfaceAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.setExternalSim(useExternalSim);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /** See ISupplicant.hal for documentation */
    public boolean enableAutoReconnect(boolean enable) {
        synchronized (mLock) {
            final String methodStr = "enableAutoReconnect";
            if (!checkSupplicantAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicantStaIface.enableAutoReconnect(enable);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Set the debug log level for wpa_supplicant
     *
     * @param turnOnVerbose Whether to turn on verbose logging or not.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setLogLevel(boolean turnOnVerbose) {
        synchronized (mLock) {
            int logLevel = turnOnVerbose
                    ? ISupplicant.DebugLevel.DEBUG
                    : ISupplicant.DebugLevel.INFO;
            return setDebugParams(logLevel, false, false);
        }
    }

    /** See ISupplicant.hal for documentation */
    private boolean setDebugParams(int level, boolean showTimestamp, boolean showKeys) {
        synchronized (mLock) {
            final String methodStr = "setDebugParams";
            if (!checkSupplicantAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status =
                        mISupplicant.setDebugParams(level, showTimestamp, showKeys);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Set concurrency priority between P2P & STA operations.
     *
     * @param isStaHigherPriority Set to true to prefer STA over P2P during concurrency operations,
     *                            false otherwise.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setConcurrencyPriority(boolean isStaHigherPriority) {
        synchronized (mLock) {
            if (isStaHigherPriority) {
                return setConcurrencyPriority(IfaceType.STA);
            } else {
                return setConcurrencyPriority(IfaceType.P2P);
            }
        }
    }

    /** See ISupplicant.hal for documentation */
    private boolean setConcurrencyPriority(int type) {
        synchronized (mLock) {
            final String methodStr = "setConcurrencyPriority";
            if (!checkSupplicantAndLogFailure(methodStr)) return false;
            try {
                SupplicantStatus status = mISupplicant.setConcurrencyPriority(type);
                return checkStatusAndLogFailure(status, methodStr);
            } catch (RemoteException e) {
                handleRemoteException(e, methodStr);
                return false;
            }
        }
    }

    /**
     * Returns false if Supplicant is null, and logs failure to call methodStr
     */
    private boolean checkSupplicantAndLogFailure(final String methodStr) {
        synchronized (mLock) {
            if (mISupplicant == null) {
                Log.e(TAG, "Can't call " + methodStr + ", ISupplicant is null");
                return false;
            }
            return true;
        }
    }

    /**
     * Returns false if SupplicantStaIface is null, and logs failure to call methodStr
     */
    private boolean checkSupplicantStaIfaceAndLogFailure(final String methodStr) {
        synchronized (mLock) {
            if (mISupplicantStaIface == null) {
                Log.e(TAG, "Can't call " + methodStr + ", ISupplicantStaIface is null");
                return false;
            }
            return true;
        }
    }

    /**
     * Returns true if provided status code is SUCCESS, logs debug message and returns false
     * otherwise
     */
    private boolean checkStatusAndLogFailure(SupplicantStatus status,
            final String methodStr) {
        synchronized (mLock) {
            if (status.code != SupplicantStatusCode.SUCCESS) {
                Log.e(TAG, "ISupplicantStaIface." + methodStr + " failed: "
                        + supplicantStatusCodeToString(status.code) + ", " + status.debugMessage);
                return false;
            } else {
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "ISupplicantStaIface." + methodStr + " succeeded");
                }
                return true;
            }
        }
    }

    /**
     * Helper function to log callbacks.
     */
    private void logCallback(final String methodStr) {
        synchronized (mLock) {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "ISupplicantStaIfaceCallback." + methodStr + " received");
            }
        }
    }


    private void handleRemoteException(RemoteException e, String methodStr) {
        synchronized (mLock) {
            supplicantServiceDiedHandler();
            Log.e(TAG, "ISupplicantStaIface." + methodStr + " failed with exception", e);
        }
    }

    /**
     * Converts SupplicantStatus code values to strings for debug logging
     * TODO(b/34811152) Remove this, or make it more break resistance
     */
    public static String supplicantStatusCodeToString(int code) {
        switch (code) {
            case 0:
                return "SUCCESS";
            case 1:
                return "FAILURE_UNKNOWN";
            case 2:
                return "FAILURE_ARGS_INVALID";
            case 3:
                return "FAILURE_IFACE_INVALID";
            case 4:
                return "FAILURE_IFACE_UNKNOWN";
            case 5:
                return "FAILURE_IFACE_EXISTS";
            case 6:
                return "FAILURE_IFACE_DISABLED";
            case 7:
                return "FAILURE_IFACE_NOT_DISCONNECTED";
            case 8:
                return "FAILURE_NETWORK_INVALID";
            case 9:
                return "FAILURE_NETWORK_UNKNOWN";
            default:
                return "??? UNKNOWN_CODE";
        }
    }


    /**
     * Converts the Wps config method string to the equivalent enum value.
     */
    private static short stringToWpsConfigMethod(String configMethod) {
        switch (configMethod) {
            case "usba":
                return WpsConfigMethods.USBA;
            case "ethernet":
                return WpsConfigMethods.ETHERNET;
            case "label":
                return WpsConfigMethods.LABEL;
            case "display":
                return WpsConfigMethods.DISPLAY;
            case "int_nfc_token":
                return WpsConfigMethods.INT_NFC_TOKEN;
            case "ext_nfc_token":
                return WpsConfigMethods.EXT_NFC_TOKEN;
            case "nfc_interface":
                return WpsConfigMethods.NFC_INTERFACE;
            case "push_button":
                return WpsConfigMethods.PUSHBUTTON;
            case "keypad":
                return WpsConfigMethods.KEYPAD;
            case "virtual_push_button":
                return WpsConfigMethods.VIRT_PUSHBUTTON;
            case "physical_push_button":
                return WpsConfigMethods.PHY_PUSHBUTTON;
            case "p2ps":
                return WpsConfigMethods.P2PS;
            case "virtual_display":
                return WpsConfigMethods.VIRT_DISPLAY;
            case "physical_display":
                return WpsConfigMethods.PHY_DISPLAY;
            default:
                throw new IllegalArgumentException(
                        "Invalid WPS config method: " + configMethod);
        }
    }

    /**
     * Converts the supplicant state received from HIDL to the equivalent framework state.
     */
    private static SupplicantState supplicantHidlStateToFrameworkState(int state) {
        switch (state) {
            case ISupplicantStaIfaceCallback.State.DISCONNECTED:
                return SupplicantState.DISCONNECTED;
            case ISupplicantStaIfaceCallback.State.IFACE_DISABLED:
                return SupplicantState.INTERFACE_DISABLED;
            case ISupplicantStaIfaceCallback.State.INACTIVE:
                return SupplicantState.INACTIVE;
            case ISupplicantStaIfaceCallback.State.SCANNING:
                return SupplicantState.SCANNING;
            case ISupplicantStaIfaceCallback.State.AUTHENTICATING:
                return SupplicantState.AUTHENTICATING;
            case ISupplicantStaIfaceCallback.State.ASSOCIATING:
                return SupplicantState.ASSOCIATING;
            case ISupplicantStaIfaceCallback.State.ASSOCIATED:
                return SupplicantState.ASSOCIATED;
            case ISupplicantStaIfaceCallback.State.FOURWAY_HANDSHAKE:
                return SupplicantState.FOUR_WAY_HANDSHAKE;
            case ISupplicantStaIfaceCallback.State.GROUP_HANDSHAKE:
                return SupplicantState.GROUP_HANDSHAKE;
            case ISupplicantStaIfaceCallback.State.COMPLETED:
                return SupplicantState.COMPLETED;
            default:
                throw new IllegalArgumentException("Invalid state: " + state);
        }
    }

    private static class Mutable<E> {
        public E value;

        Mutable() {
            value = null;
        }

        Mutable(E value) {
            this.value = value;
        }
    }

    private class SupplicantStaIfaceHalCallback extends ISupplicantStaIfaceCallback.Stub {
        private static final int WLAN_REASON_IE_IN_4WAY_DIFFERS = 17; // IEEE 802.11i
        private boolean mStateIsFourway = false; // Used to help check for PSK password mismatch

        /**
         * Parses the provided payload into an ANQP element.
         *
         * @param infoID  Element type.
         * @param payload Raw payload bytes.
         * @return AnqpElement instance on success, null on failure.
         */
        private ANQPElement parseAnqpElement(Constants.ANQPElementType infoID,
                                             ArrayList<Byte> payload) {
            synchronized (mLock) {
                try {
                    return Constants.getANQPElementID(infoID) != null
                            ? ANQPParser.parseElement(
                            infoID, ByteBuffer.wrap(NativeUtil.byteArrayFromArrayList(payload)))
                            : ANQPParser.parseHS20Element(
                            infoID, ByteBuffer.wrap(NativeUtil.byteArrayFromArrayList(payload)));
                } catch (IOException | BufferUnderflowException e) {
                    Log.e(TAG, "Failed parsing ANQP element payload: " + infoID, e);
                    return null;
                }
            }
        }

        /**
         * Parse the ANQP element data and add to the provided elements map if successful.
         *
         * @param elementsMap Map to add the parsed out element to.
         * @param infoID  Element type.
         * @param payload Raw payload bytes.
         */
        private void addAnqpElementToMap(Map<Constants.ANQPElementType, ANQPElement> elementsMap,
                                         Constants.ANQPElementType infoID,
                                         ArrayList<Byte> payload) {
            synchronized (mLock) {
                if (payload == null || payload.isEmpty()) return;
                ANQPElement element = parseAnqpElement(infoID, payload);
                if (element != null) {
                    elementsMap.put(infoID, element);
                }
            }
        }

        @Override
        public void onNetworkAdded(int id) {
            synchronized (mLock) {
                logCallback("onNetworkAdded");
            }
        }

        @Override
        public void onNetworkRemoved(int id) {
            synchronized (mLock) {
                logCallback("onNetworkRemoved");
            }
        }

        @Override
        public void onStateChanged(int newState, byte[/* 6 */] bssid, int id,
                                   ArrayList<Byte> ssid) {
            synchronized (mLock) {
                logCallback("onStateChanged");
                SupplicantState newSupplicantState = supplicantHidlStateToFrameworkState(newState);
                WifiSsid wifiSsid =
                        WifiSsid.createFromByteArray(NativeUtil.byteArrayFromArrayList(ssid));
                String bssidStr = NativeUtil.macAddressFromByteArray(bssid);
                mStateIsFourway = (newState == ISupplicantStaIfaceCallback.State.FOURWAY_HANDSHAKE);
                if (newSupplicantState == SupplicantState.COMPLETED) {
                    mWifiMonitor.broadcastNetworkConnectionEvent(
                            mIfaceName, getCurrentNetworkId(), bssidStr);
                }
                mWifiMonitor.broadcastSupplicantStateChangeEvent(
                        mIfaceName, getCurrentNetworkId(), wifiSsid, bssidStr, newSupplicantState);
            }
        }

        @Override
        public void onAnqpQueryDone(byte[/* 6 */] bssid,
                                    ISupplicantStaIfaceCallback.AnqpData data,
                                    ISupplicantStaIfaceCallback.Hs20AnqpData hs20Data) {
            synchronized (mLock) {
                logCallback("onAnqpQueryDone");
                Map<Constants.ANQPElementType, ANQPElement> elementsMap = new HashMap<>();
                addAnqpElementToMap(elementsMap, ANQPVenueName, data.venueName);
                addAnqpElementToMap(elementsMap, ANQPRoamingConsortium, data.roamingConsortium);
                addAnqpElementToMap(
                        elementsMap, ANQPIPAddrAvailability, data.ipAddrTypeAvailability);
                addAnqpElementToMap(elementsMap, ANQPNAIRealm, data.naiRealm);
                addAnqpElementToMap(elementsMap, ANQP3GPPNetwork, data.anqp3gppCellularNetwork);
                addAnqpElementToMap(elementsMap, ANQPDomName, data.domainName);
                addAnqpElementToMap(elementsMap, HSFriendlyName, hs20Data.operatorFriendlyName);
                addAnqpElementToMap(elementsMap, HSWANMetrics, hs20Data.wanMetrics);
                addAnqpElementToMap(elementsMap, HSConnCapability, hs20Data.connectionCapability);
                addAnqpElementToMap(elementsMap, HSOSUProviders, hs20Data.osuProvidersList);
                mWifiMonitor.broadcastAnqpDoneEvent(
                        mIfaceName, new AnqpEvent(NativeUtil.macAddressToLong(bssid), elementsMap));
            }
        }

        @Override
        public void onHs20IconQueryDone(byte[/* 6 */] bssid, String fileName,
                                        ArrayList<Byte> data) {
            synchronized (mLock) {
                logCallback("onHs20IconQueryDone");
                mWifiMonitor.broadcastIconDoneEvent(
                        mIfaceName,
                        new IconEvent(NativeUtil.macAddressToLong(bssid), fileName, data.size(),
                                NativeUtil.byteArrayFromArrayList(data)));
            }
        }

        @Override
        public void onHs20SubscriptionRemediation(byte[/* 6 */] bssid, byte osuMethod, String url) {
            synchronized (mLock) {
                logCallback("onHs20SubscriptionRemediation");
                mWifiMonitor.broadcastWnmEvent(
                        mIfaceName,
                        new WnmData(NativeUtil.macAddressToLong(bssid), url, osuMethod));
            }
        }

        @Override
        public void onHs20DeauthImminentNotice(byte[/* 6 */] bssid, int reasonCode,
                                               int reAuthDelayInSec, String url) {
            synchronized (mLock) {
                logCallback("onHs20DeauthImminentNotice");
                mWifiMonitor.broadcastWnmEvent(
                        mIfaceName,
                        new WnmData(NativeUtil.macAddressToLong(bssid), url,
                                reasonCode == WnmData.ESS, reAuthDelayInSec));
            }
        }

        @Override
        public void onDisconnected(byte[/* 6 */] bssid, boolean locallyGenerated, int reasonCode) {
            synchronized (mLock) {
                logCallback("onDisconnected");
                if (mVerboseLoggingEnabled) {
                    Log.e(TAG, "onDisconnected 4way=" + mStateIsFourway
                            + " locallyGenerated=" + locallyGenerated
                            + " reasonCode=" + reasonCode);
                }
                if (mStateIsFourway
                        && (!locallyGenerated || reasonCode != WLAN_REASON_IE_IN_4WAY_DIFFERS)) {
                    mWifiMonitor.broadcastAuthenticationFailureEvent(
                            mIfaceName, WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD);
                }
                mWifiMonitor.broadcastNetworkDisconnectionEvent(
                        mIfaceName, locallyGenerated ? 1 : 0, reasonCode,
                        NativeUtil.macAddressFromByteArray(bssid));
            }
        }

        @Override
        public void onAssociationRejected(byte[/* 6 */] bssid, int statusCode, boolean timedOut) {
            synchronized (mLock) {
                logCallback("onAssociationRejected");
                mWifiMonitor.broadcastAssociationRejectionEvent(mIfaceName, statusCode, timedOut,
                        NativeUtil.macAddressFromByteArray(bssid));
            }
        }

        @Override
        public void onAuthenticationTimeout(byte[/* 6 */] bssid) {
            synchronized (mLock) {
                logCallback("onAuthenticationTimeout");
                mWifiMonitor.broadcastAuthenticationFailureEvent(
                        mIfaceName, WifiManager.ERROR_AUTH_FAILURE_TIMEOUT);
            }
        }

        @Override
        public void onBssidChanged(byte reason, byte[/* 6 */] bssid) {
            synchronized (mLock) {
                logCallback("onBssidChanged");
                if (reason == BssidChangeReason.ASSOC_START) {
                    mWifiMonitor.broadcastTargetBssidEvent(
                            mIfaceName, NativeUtil.macAddressFromByteArray(bssid));
                } else if (reason == BssidChangeReason.ASSOC_COMPLETE) {
                    mWifiMonitor.broadcastAssociatedBssidEvent(
                            mIfaceName, NativeUtil.macAddressFromByteArray(bssid));
                }
            }
        }

        @Override
        public void onEapFailure() {
            synchronized (mLock) {
                logCallback("onEapFailure");
                mWifiMonitor.broadcastAuthenticationFailureEvent(
                        mIfaceName, WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE);
            }
        }

        @Override
        public void onWpsEventSuccess() {
            logCallback("onWpsEventSuccess");
            synchronized (mLock) {
                mWifiMonitor.broadcastWpsSuccessEvent(mIfaceName);
            }
        }

        @Override
        public void onWpsEventFail(byte[/* 6 */] bssid, short configError, short errorInd) {
            synchronized (mLock) {
                logCallback("onWpsEventFail");
                if (configError == WpsConfigError.MSG_TIMEOUT
                        && errorInd == WpsErrorIndication.NO_ERROR) {
                    mWifiMonitor.broadcastWpsTimeoutEvent(mIfaceName);
                } else {
                    mWifiMonitor.broadcastWpsFailEvent(mIfaceName, configError, errorInd);
                }
            }
        }

        @Override
        public void onWpsEventPbcOverlap() {
            synchronized (mLock) {
                logCallback("onWpsEventPbcOverlap");
                mWifiMonitor.broadcastWpsOverlapEvent(mIfaceName);
            }
        }

        @Override
        public void onExtRadioWorkStart(int id) {
            synchronized (mLock) {
                logCallback("onExtRadioWorkStart");
            }
        }

        @Override
        public void onExtRadioWorkTimeout(int id) {
            synchronized (mLock) {
                logCallback("onExtRadioWorkTimeout");
            }
        }
    }

    private static void logd(String s) {
        Log.d(TAG, s);
    }

    private static void logi(String s) {
        Log.i(TAG, s);
    }

    private static void loge(String s) {
        Log.e(TAG, s);
    }
}
