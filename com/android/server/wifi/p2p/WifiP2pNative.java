/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.wifi.p2p;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.wifi.V1_0.IWifiP2pIface;
import android.hardware.wifi.V1_0.IfaceType;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pGroupList;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.wifi.HalDeviceManager;
import com.android.server.wifi.PropertyService;
import com.android.server.wifi.WifiVendorHal;

/**
 * Native calls for bring up/shut down of the supplicant daemon and for
 * sending requests to the supplicant daemon
 *
 * {@hide}
 */
public class WifiP2pNative {
    private static final String TAG = "WifiP2pNative";
    private boolean mVerboseLoggingEnabled = false;
    private final SupplicantP2pIfaceHal mSupplicantP2pIfaceHal;
    private final HalDeviceManager mHalDeviceManager;
    private final PropertyService mPropertyService;
    private final WifiVendorHal mWifiVendorHal;
    private IWifiP2pIface mIWifiP2pIface;
    private InterfaceAvailableListenerInternal mInterfaceAvailableListener;
    private InterfaceDestroyedListenerInternal mInterfaceDestroyedListener;

    // Internal callback registered to HalDeviceManager.
    private class InterfaceAvailableListenerInternal implements
            HalDeviceManager.InterfaceAvailableForRequestListener {
        private final HalDeviceManager.InterfaceAvailableForRequestListener mExternalListener;

        InterfaceAvailableListenerInternal(
                HalDeviceManager.InterfaceAvailableForRequestListener externalListener) {
            mExternalListener = externalListener;
        }

        @Override
        public void onAvailabilityChanged(boolean isAvailable) {
            Log.d(TAG, "P2P InterfaceAvailableListener " + isAvailable);
            // We need another level of abstraction here. When a P2P interface is created,
            // we should mask the availability change callback from WifiP2pService.
            // This is because when the P2P interface is created, we'll get a callback
            // indicating that we can no longer create a new P2P interface. We don't need to
            // propagate this internal state to WifiP2pServiceImpl.
            if (mIWifiP2pIface != null && !isAvailable) {
                Log.i(TAG, "Masking interface non-availability callback because "
                        + "we created a P2P iface");
                return;
            }
            mExternalListener.onAvailabilityChanged(isAvailable);
        }
    }

    // Internal callback registered to HalDeviceManager.
    private class InterfaceDestroyedListenerInternal implements
            HalDeviceManager.InterfaceDestroyedListener {
        private final HalDeviceManager.InterfaceDestroyedListener mExternalListener;
        private boolean mValid;

        InterfaceDestroyedListenerInternal(
                HalDeviceManager.InterfaceDestroyedListener externalListener) {
            mExternalListener = externalListener;
            mValid = true;
        }

        public void teardownAndInvalidate(@Nullable String ifaceName) {
            if (!TextUtils.isEmpty(ifaceName)) {
                mSupplicantP2pIfaceHal.teardownIface(ifaceName);
            }
            mIWifiP2pIface = null;
            mValid = false;
        }

        @Override
        public void onDestroyed(String ifaceName) {
            Log.d(TAG, "P2P InterfaceDestroyedListener " + ifaceName);
            if (!mValid) {
                Log.d(TAG, "Ignoring stale interface destroyed listener");
                return;
            }
            teardownAndInvalidate(ifaceName);
            mExternalListener.onDestroyed(ifaceName);
        }
    }

    public WifiP2pNative(WifiVendorHal wifiVendorHal,
            SupplicantP2pIfaceHal p2pIfaceHal, HalDeviceManager halDeviceManager,
            PropertyService propertyService) {
        mWifiVendorHal = wifiVendorHal;
        mSupplicantP2pIfaceHal = p2pIfaceHal;
        mHalDeviceManager = halDeviceManager;
        mPropertyService = propertyService;
    }

    /**
     * Enable verbose logging for all sub modules.
     */
    public void enableVerboseLogging(int verbose) {
        mVerboseLoggingEnabled = verbose > 0;
        SupplicantP2pIfaceHal.enableVerboseLogging(verbose);
    }

    private static final int CONNECT_TO_SUPPLICANT_SAMPLING_INTERVAL_MS = 100;
    private static final int CONNECT_TO_SUPPLICANT_MAX_SAMPLES = 50;
    /**
     * This method is called to wait for establishing connection to wpa_supplicant.
     *
     * @return true if connection is established, false otherwise.
     */
    private boolean waitForSupplicantConnection() {
        // Start initialization if not already started.
        if (!mSupplicantP2pIfaceHal.isInitializationStarted()
                && !mSupplicantP2pIfaceHal.initialize()) {
            return false;
        }
        int connectTries = 0;
        while (connectTries++ < CONNECT_TO_SUPPLICANT_MAX_SAMPLES) {
            // Check if the initialization is complete.
            if (mSupplicantP2pIfaceHal.isInitializationComplete()) {
                return true;
            }
            try {
                Thread.sleep(CONNECT_TO_SUPPLICANT_SAMPLING_INTERVAL_MS);
            } catch (InterruptedException ignore) {
            }
        }
        return false;
    }

    /**
     * Close supplicant connection.
     */
    public void closeSupplicantConnection() {
        // Nothing to do for HIDL.
    }

    /**
     * Returns whether HAL (HIDL) is supported on this device or not.
     */
    public boolean isHalInterfaceSupported() {
        return mHalDeviceManager.isSupported();
    }

    private static final String P2P_IFACE_NAME = "p2p0";
    private static final String P2P_INTERFACE_PROPERTY = "wifi.direct.interface";
    /**
     * Helper function to handle creation of P2P iface.
     * For devices which do not the support the HAL, this will bypass HalDeviceManager &
     * teardown any existing iface.
     */
    private String createP2pIface(Handler handler) {
        if (mHalDeviceManager.isSupported()) {
            mIWifiP2pIface = mHalDeviceManager
                                .createP2pIface(mInterfaceDestroyedListener, handler);
            if (mIWifiP2pIface == null) {
                Log.e(TAG, "Failed to create P2p iface in HalDeviceManager");
                return null;
            }
            String ifaceName = HalDeviceManager.getName(mIWifiP2pIface);
            if (TextUtils.isEmpty(ifaceName)) {
                Log.e(TAG, "Failed to get p2p iface name");
                teardownInterface();
                return null;
            }
            return ifaceName;
        } else {
            Log.i(TAG, "Vendor Hal is not supported, ignoring createP2pIface.");
            return mPropertyService.getString(P2P_INTERFACE_PROPERTY, P2P_IFACE_NAME);
        }
    }

    /**
     * Register for an interface available callbacks from HalDeviceManager.
     *
     * @param listener callback to be invoked when the interface is available/not available.
     */
    public void registerInterfaceAvailableListener(
            @NonNull HalDeviceManager.InterfaceAvailableForRequestListener listener,
            Handler handler) {
        mInterfaceAvailableListener = new InterfaceAvailableListenerInternal(listener);
        // The interface available callbacks are cleared on every HAL stop, so need to
        // re-register these callbacks on every start.
        mHalDeviceManager.registerStatusListener(() -> {
            if (mHalDeviceManager.isStarted()) {
                Log.i(TAG, "Registering for interface available listener");
                mHalDeviceManager.registerInterfaceAvailableForRequestListener(
                        IfaceType.P2P, mInterfaceAvailableListener, handler);
            }
        }, handler);
        if (mHalDeviceManager.isStarted()) {
            mHalDeviceManager.registerInterfaceAvailableForRequestListener(
                    IfaceType.P2P, mInterfaceAvailableListener, handler);
        }
    }

    /**
     * Setup Interface for P2p mode.
     *
     * @param destroyedListener Listener to be invoked when the interface is destroyed.
     * @param handler Handler to be used for invoking the destroyedListener.
     */
    public String setupInterface(
            @NonNull HalDeviceManager.InterfaceDestroyedListener destroyedListener,
            Handler handler) {
        Log.d(TAG, "Setup P2P interface");
        if (mIWifiP2pIface == null) {
            mInterfaceDestroyedListener =
                    new InterfaceDestroyedListenerInternal(destroyedListener);
            String ifaceName = createP2pIface(handler);
            if (ifaceName == null) {
                Log.e(TAG, "Failed to create P2p iface");
                return null;
            }
            if (!waitForSupplicantConnection()) {
                Log.e(TAG, "Failed to connect to supplicant");
                teardownInterface();
                return null;
            }
            if (!mSupplicantP2pIfaceHal.setupIface(ifaceName)) {
                Log.e(TAG, "Failed to setup P2p iface in supplicant");
                teardownInterface();
                return null;
            }
            Log.i(TAG, "P2P interface setup completed");
            return ifaceName;
        } else {
            Log.i(TAG, "P2P interface is already existed");
            return mHalDeviceManager.isSupported()
                ? HalDeviceManager.getName(mIWifiP2pIface)
                : mPropertyService.getString(P2P_INTERFACE_PROPERTY, P2P_IFACE_NAME);
        }
    }

    /**
     * Teardown P2p interface.
     */
    public void teardownInterface() {
        Log.d(TAG, "Teardown P2P interface");
        if (mHalDeviceManager.isSupported()) {
            if (mIWifiP2pIface != null) {
                String ifaceName = HalDeviceManager.getName(mIWifiP2pIface);
                mHalDeviceManager.removeIface(mIWifiP2pIface);
                mInterfaceDestroyedListener.teardownAndInvalidate(ifaceName);
                Log.i(TAG, "P2P interface teardown completed");
            }
        } else {
            Log.i(TAG, "HAL (HIDL) is not supported. Destroy listener for the interface.");
            String ifaceName = mPropertyService.getString(P2P_INTERFACE_PROPERTY, P2P_IFACE_NAME);
            mInterfaceDestroyedListener.teardownAndInvalidate(ifaceName);
        }
    }

    /**
     * Set WPS device name.
     *
     * @param name String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setDeviceName(String name) {
        return mSupplicantP2pIfaceHal.setWpsDeviceName(name);
    }

    /**
     * Populate list of available networks or update existing list.
     *
     * @return true, if list has been modified.
     */
    public boolean p2pListNetworks(WifiP2pGroupList groups) {
        return mSupplicantP2pIfaceHal.loadGroups(groups);
    }

    /**
     * Initiate WPS Push Button setup.
     * The PBC operation requires that a button is also pressed at the
     * AP/Registrar at about the same time (2 minute window).
     *
     * @param iface Group interface name to use.
     * @param bssid BSSID of the AP. Use zero'ed bssid to indicate wildcard.
     * @return true, if operation was successful.
     */
    public boolean startWpsPbc(String iface, String bssid) {
        return mSupplicantP2pIfaceHal.startWpsPbc(iface, bssid);
    }

    /**
     * Initiate WPS Pin Keypad setup.
     *
     * @param iface Group interface name to use.
     * @param pin 8 digit pin to be used.
     * @return true, if operation was successful.
     */
    public boolean startWpsPinKeypad(String iface, String pin) {
        return mSupplicantP2pIfaceHal.startWpsPinKeypad(iface, pin);
    }

    /**
     * Initiate WPS Pin Display setup.
     *
     * @param iface Group interface name to use.
     * @param bssid BSSID of the AP. Use zero'ed bssid to indicate wildcard.
     * @return generated pin if operation was successful, null otherwise.
     */
    public String startWpsPinDisplay(String iface, String bssid) {
        return mSupplicantP2pIfaceHal.startWpsPinDisplay(iface, bssid);
    }

    /**
     * Remove network with provided id.
     *
     * @param netId Id of the network to lookup.
     * @return true, if operation was successful.
     */
    public boolean removeP2pNetwork(int netId) {
        return mSupplicantP2pIfaceHal.removeNetwork(netId);
    }

    /**
     * Set WPS device name.
     *
     * @param name String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setP2pDeviceName(String name) {
        return mSupplicantP2pIfaceHal.setWpsDeviceName(name);
    }

    /**
     * Set WPS device type.
     *
     * @param type Type specified as a string. Used format: <categ>-<OUI>-<subcateg>
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setP2pDeviceType(String type) {
        return mSupplicantP2pIfaceHal.setWpsDeviceType(type);
    }

    /**
     * Set WPS config methods
     *
     * @param cfg List of config methods.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setConfigMethods(String cfg) {
        return mSupplicantP2pIfaceHal.setWpsConfigMethods(cfg);
    }

    /**
     * Set the postfix to be used for P2P SSID's.
     *
     * @param postfix String to be appended to SSID.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean setP2pSsidPostfix(String postfix) {
        return mSupplicantP2pIfaceHal.setSsidPostfix(postfix);
    }

    /**
     * Set the Maximum idle time in seconds for P2P groups.
     * This value controls how long a P2P group is maintained after there
     * is no other members in the group. As a group owner, this means no
     * associated stations in the group. As a P2P client, this means no
     * group owner seen in scan results.
     *
     * @param iface Group interface name to use.
     * @param time Timeout value in seconds.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean setP2pGroupIdle(String iface, int time) {
        return mSupplicantP2pIfaceHal.setGroupIdle(iface, time);
    }

    /**
     * Turn on/off power save mode for the interface.
     *
     * @param iface Group interface name to use.
     * @param enabled Indicate if power save is to be turned on/off.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean setP2pPowerSave(String iface, boolean enabled) {
        return mSupplicantP2pIfaceHal.setPowerSave(iface, enabled);
    }

    /**
     * Enable/Disable Wifi Display.
     *
     * @param enable true to enable, false to disable.
     * @return true, if operation was successful.
     */
    public boolean setWfdEnable(boolean enable) {
        return mSupplicantP2pIfaceHal.enableWfd(enable);
    }

    /**
     * Set Wifi Display device info.
     *
     * @param hex WFD device info as described in section 5.1.2 of WFD technical
     *        specification v1.0.0.
     * @return true, if operation was successful.
     */
    public boolean setWfdDeviceInfo(String hex) {
        return mSupplicantP2pIfaceHal.setWfdDeviceInfo(hex);
    }

    /**
     * Initiate a P2P service discovery indefinitely.
     * Will trigger {@link WifiP2pMonitor#P2P_DEVICE_FOUND_EVENT} on finding devices.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pFind() {
        return p2pFind(0);
    }

    /**
     * Initiate a P2P service discovery with a (optional) timeout.
     *
     * @param timeout Max time to be spent is peforming discovery.
     *        Set to 0 to indefinely continue discovery untill and explicit
     *        |stopFind| is sent.
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pFind(int timeout) {
        return mSupplicantP2pIfaceHal.find(timeout);
    }

    /**
     * Stop an ongoing P2P service discovery.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pStopFind() {
        return mSupplicantP2pIfaceHal.stopFind();
    }

    /**
     * Configure Extended Listen Timing.
     *
     * If enabled, listen state must be entered every |intervalInMillis| for at
     * least |periodInMillis|. Both values have acceptable range of 1-65535
     * (with interval obviously having to be larger than or equal to duration).
     * If the P2P module is not idle at the time the Extended Listen Timing
     * timeout occurs, the Listen State operation must be skipped.
     *
     * @param enable Enables or disables listening.
     * @param period Period in milliseconds.
     * @param interval Interval in milliseconds.
     *
     * @return true, if operation was successful.
     */
    public boolean p2pExtListen(boolean enable, int period, int interval) {
        return mSupplicantP2pIfaceHal.configureExtListen(enable, period, interval);
    }

    /**
     * Set P2P Listen channel.
     *
     * When specifying a social channel on the 2.4 GHz band (1/6/11) there is no
     * need to specify the operating class since it defaults to 81. When
     * specifying a social channel on the 60 GHz band (2), specify the 60 GHz
     * operating class (180).
     *
     * @param lc Wifi channel. eg, 1, 6, 11.
     * @param oc Operating Class indicates the channel set of the AP
     *        indicated by this BSSID
     *
     * @return true, if operation was successful.
     */
    public boolean p2pSetChannel(int lc, int oc) {
        return mSupplicantP2pIfaceHal.setListenChannel(lc, oc);
    }

    /**
     * Flush P2P peer table and state.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pFlush() {
        return mSupplicantP2pIfaceHal.flush();
    }

    /**
     * Start P2P group formation with a discovered P2P peer. This includes
     * optional group owner negotiation, group interface setup, provisioning,
     * and establishing data connection.
     *
     * @param config Configuration to use to connect to remote device.
     * @param joinExistingGroup Indicates that this is a command to join an
     *        existing group as a client. It skips the group owner negotiation
     *        part. This must send a Provision Discovery Request message to the
     *        target group owner before associating for WPS provisioning.
     *
     * @return String containing generated pin, if selected provision method
     *        uses PIN.
     */
    public String p2pConnect(WifiP2pConfig config, boolean joinExistingGroup) {
        return mSupplicantP2pIfaceHal.connect(config, joinExistingGroup);
    }

    /**
     * Cancel an ongoing P2P group formation and joining-a-group related
     * operation. This operation unauthorizes the specific peer device (if any
     * had been authorized to start group formation), stops P2P find (if in
     * progress), stops pending operations for join-a-group, and removes the
     * P2P group interface (if one was used) that is in the WPS provisioning
     * step. If the WPS provisioning step has been completed, the group is not
     * terminated.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pCancelConnect() {
        return mSupplicantP2pIfaceHal.cancelConnect();
    }

    /**
     * Send P2P provision discovery request to the specified peer. The
     * parameters for this command are the P2P device address of the peer and the
     * desired configuration method.
     *
     * @param config Config class describing peer setup.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pProvisionDiscovery(WifiP2pConfig config) {
        return mSupplicantP2pIfaceHal.provisionDiscovery(config);
    }

    /**
     * Set up a P2P group owner manually.
     * This is a helper method that invokes groupAdd(networkId, isPersistent) internally.
     *
     * @param persistent Used to request a persistent group to be formed.
     *
     * @return true, if operation was successful.
     */
    public boolean p2pGroupAdd(boolean persistent) {
        return mSupplicantP2pIfaceHal.groupAdd(persistent);
    }

    /**
     * Set up a P2P group owner manually (i.e., without group owner
     * negotiation with a specific peer). This is also known as autonomous
     * group owner.
     *
     * @param netId Used to specify the restart of a persistent group.
     *
     * @return true, if operation was successful.
     */
    public boolean p2pGroupAdd(int netId) {
        return mSupplicantP2pIfaceHal.groupAdd(netId, true);
    }

    /**
     * Set up a P2P group as Group Owner or join a group with a configuration.
     *
     * @param config Used to specify config for setting up a P2P group
     *
     * @return true, if operation was successful.
     */
    public boolean p2pGroupAdd(WifiP2pConfig config, boolean join) {
        int freq = 0;
        switch (config.groupOwnerBand) {
            case WifiP2pConfig.GROUP_OWNER_BAND_2GHZ:
                freq = 2;
                break;
            case WifiP2pConfig.GROUP_OWNER_BAND_5GHZ:
                freq = 5;
                break;
            // treat it as frequency.
            default:
                freq = config.groupOwnerBand;
        }
        return mSupplicantP2pIfaceHal.groupAdd(
                config.networkName,
                config.passphrase,
                (config.netId == WifiP2pGroup.PERSISTENT_NET_ID),
                freq, config.deviceAddress, join);
    }

    /**
     * Terminate a P2P group. If a new virtual network interface was used for
     * the group, it must also be removed. The network interface name of the
     * group interface is used as a parameter for this command.
     *
     * @param iface Group interface name to use.
     * @return true, if operation was successful.
     */
    public boolean p2pGroupRemove(String iface) {
        return mSupplicantP2pIfaceHal.groupRemove(iface);
    }

    /**
     * Reject connection attempt from a peer (specified with a device
     * address). This is a mechanism to reject a pending group owner negotiation
     * with a peer and request to automatically block any further connection or
     * discovery of the peer.
     *
     * @param deviceAddress MAC address of the device to reject.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pReject(String deviceAddress) {
        return mSupplicantP2pIfaceHal.reject(deviceAddress);
    }

    /**
     * Invite a device to a persistent group.
     * If the peer device is the group owner of the persistent group, the peer
     * parameter is not needed. Otherwise it is used to specify which
     * device to invite. |goDeviceAddress| parameter may be used to override
     * the group owner device address for Invitation Request should it not be
     * known for some reason (this should not be needed in most cases).
     *
     * @param group Group object to use.
     * @param deviceAddress MAC address of the device to invite.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pInvite(WifiP2pGroup group, String deviceAddress) {
        return mSupplicantP2pIfaceHal.invite(group, deviceAddress);
    }

    /**
     * Reinvoke a device from a persistent group.
     *
     * @param netId Used to specify the persistent group.
     * @param deviceAddress MAC address of the device to reinvoke.
     *
     * @return true, if operation was successful.
     */
    public boolean p2pReinvoke(int netId, String deviceAddress) {
        return mSupplicantP2pIfaceHal.reinvoke(netId, deviceAddress);
    }

    /**
     * Gets the operational SSID of the device.
     *
     * @param deviceAddress MAC address of the peer.
     *
     * @return SSID of the device.
     */
    public String p2pGetSsid(String deviceAddress) {
        return mSupplicantP2pIfaceHal.getSsid(deviceAddress);
    }

    /**
     * Gets the MAC address of the device.
     *
     * @return MAC address of the device.
     */
    public String p2pGetDeviceAddress() {
        return mSupplicantP2pIfaceHal.getDeviceAddress();
    }

    /**
     * Gets the capability of the group which the device is a
     * member of.
     *
     * @param deviceAddress MAC address of the peer.
     *
     * @return combination of |GroupCapabilityMask| values.
     */
    public int getGroupCapability(String deviceAddress) {
        return mSupplicantP2pIfaceHal.getGroupCapability(deviceAddress);
    }

    /**
     * This command can be used to add a upnp/bonjour service.
     *
     * @param servInfo List of service queries.
     *
     * @return true, if operation was successful.
     */
    public boolean p2pServiceAdd(WifiP2pServiceInfo servInfo) {
        return mSupplicantP2pIfaceHal.serviceAdd(servInfo);
    }

    /**
     * This command can be used to remove a upnp/bonjour service.
     *
     * @param servInfo List of service queries.
     *
     * @return true, if operation was successful.
     */
    public boolean p2pServiceDel(WifiP2pServiceInfo servInfo) {
        return mSupplicantP2pIfaceHal.serviceRemove(servInfo);
    }

    /**
     * This command can be used to flush all services from the
     * device.
     *
     * @return boolean value indicating whether operation was successful.
     */
    public boolean p2pServiceFlush() {
        return mSupplicantP2pIfaceHal.serviceFlush();
    }

    /**
     * Schedule a P2P service discovery request. The parameters for this command
     * are the device address of the peer device (or 00:00:00:00:00:00 for
     * wildcard query that is sent to every discovered P2P peer that supports
     * service discovery) and P2P Service Query TLV(s) as hexdump.
     *
     * @param addr MAC address of the device to discover.
     * @param query Hex dump of the query data.
     * @return identifier Identifier for the request. Can be used to cancel the
     *         request.
     */
    public String p2pServDiscReq(String addr, String query) {
        return mSupplicantP2pIfaceHal.requestServiceDiscovery(addr, query);
    }

    /**
     * Cancel a previous service discovery request.
     *
     * @param id Identifier for the request to cancel.
     * @return true, if operation was successful.
     */
    public boolean p2pServDiscCancelReq(String id) {
        return mSupplicantP2pIfaceHal.cancelServiceDiscovery(id);
    }

    /**
     * Send driver command to set Miracast mode.
     *
     * @param mode Mode of Miracast.
     *        0 = disabled
     *        1 = operating as source
     *        2 = operating as sink
     */
    public void setMiracastMode(int mode) {
        mSupplicantP2pIfaceHal.setMiracastMode(mode);
    }

    /**
     * Get NFC handover request message.
     *
     * @return select message if created successfully, null otherwise.
     */
    public String getNfcHandoverRequest() {
        return mSupplicantP2pIfaceHal.getNfcHandoverRequest();
    }

    /**
     * Get NFC handover select message.
     *
     * @return select message if created successfully, null otherwise.
     */
    public String getNfcHandoverSelect() {
        return mSupplicantP2pIfaceHal.getNfcHandoverSelect();
    }

    /**
     * Report NFC handover select message.
     *
     * @return true if reported successfully, false otherwise.
     */
    public boolean initiatorReportNfcHandover(String selectMessage) {
        return mSupplicantP2pIfaceHal.initiatorReportNfcHandover(selectMessage);
    }

    /**
     * Report NFC handover request message.
     *
     * @return true if reported successfully, false otherwise.
     */
    public boolean responderReportNfcHandover(String requestMessage) {
        return mSupplicantP2pIfaceHal.responderReportNfcHandover(requestMessage);
    }

    /**
     * Set the client list for the provided network.
     *
     * @param netId Id of the network.
     * @return  Space separated list of clients if successfull, null otherwise.
     */
    public String getP2pClientList(int netId) {
        return mSupplicantP2pIfaceHal.getClientList(netId);
    }

    /**
     * Set the client list for the provided network.
     *
     * @param netId Id of the network.
     * @param list Space separated list of clients.
     * @return true, if operation was successful.
     */
    public boolean setP2pClientList(int netId, String list) {
        return mSupplicantP2pIfaceHal.setClientList(netId, list);
    }

    /**
     * Save the current configuration to p2p_supplicant.conf.
     *
     * @return true on success, false otherwise.
     */
    public boolean saveConfig() {
        return mSupplicantP2pIfaceHal.saveConfig();
    }

    /**
     * Enable/Disable MAC randomization.
     *
     * @param enable true to enable, false to disable.
     * @return true, if operation was successful.
     */
    public boolean setMacRandomization(boolean enable) {
        return mSupplicantP2pIfaceHal.setMacRandomization(enable);
    }

    /**
     * Get the supported features
     *
     * @param ifaceName Name of the interface.
     * @return bitmask defined by WifiManager.WIFI_FEATURE_*
     */
    public long getSupportedFeatureSet(@NonNull String ifaceName) {
        return mWifiVendorHal.getSupportedFeatureSet(ifaceName);
    }
}
