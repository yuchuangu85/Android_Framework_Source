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

package com.android.server.wifi;

import android.annotation.Nullable;
import android.net.apf.ApfCapabilities;
import android.net.wifi.IApInterface;
import android.net.wifi.IClientInterface;
import android.net.wifi.RttManager;
import android.net.wifi.RttManager.ResponderConfig;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiLinkLayerStats;
import android.net.wifi.WifiScanner;
import android.net.wifi.WifiWakeReasonAndCounts;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.annotations.Immutable;
import com.android.internal.util.HexDump;
import com.android.server.connectivity.KeepalivePacketData;
import com.android.server.wifi.util.FrameParser;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;


/**
 * Native calls for bring up/shut down of the supplicant daemon and for
 * sending requests to the supplicant daemon
 *
 * {@hide}
 */
public class WifiNative {
    private final String mTAG;
    private final String mInterfaceName;
    private final SupplicantStaIfaceHal mSupplicantStaIfaceHal;
    private final WifiVendorHal mWifiVendorHal;
    private final WificondControl mWificondControl;

    public WifiNative(String interfaceName, WifiVendorHal vendorHal,
                      SupplicantStaIfaceHal staIfaceHal, WificondControl condControl) {
        mTAG = "WifiNative-" + interfaceName;
        mInterfaceName = interfaceName;
        mWifiVendorHal = vendorHal;
        mSupplicantStaIfaceHal = staIfaceHal;
        mWificondControl = condControl;
    }

    public String getInterfaceName() {
        return mInterfaceName;
    }

    /**
     * Enable verbose logging for all sub modules.
     */
    public void enableVerboseLogging(int verbose) {
        mWificondControl.enableVerboseLogging(verbose > 0 ? true : false);
        mSupplicantStaIfaceHal.enableVerboseLogging(verbose > 0);
        mWifiVendorHal.enableVerboseLogging(verbose > 0);
    }

   /********************************************************
    * Native Initialization/Deinitialization
    ********************************************************/
    public static final int SETUP_SUCCESS = 0;
    public static final int SETUP_FAILURE_HAL = 1;
    public static final int SETUP_FAILURE_WIFICOND = 2;

   /**
    * Setup wifi native for Client mode operations.
    *
    * 1. Starts the Wifi HAL and configures it in client/STA mode.
    * 2. Setup Wificond to operate in client mode and retrieve the handle to use for client
    * operations.
    *
    * @return Pair of <Integer, IClientInterface> to indicate the status and the associated wificond
    * client interface binder handler (will be null on failure).
    */
    public Pair<Integer, IClientInterface> setupForClientMode() {
        if (!startHalIfNecessary(true)) {
            Log.e(mTAG, "Failed to start HAL for client mode");
            return Pair.create(SETUP_FAILURE_HAL, null);
        }
        IClientInterface iClientInterface = mWificondControl.setupDriverForClientMode();
        if (iClientInterface == null) {
            return Pair.create(SETUP_FAILURE_WIFICOND, null);
        }
        return Pair.create(SETUP_SUCCESS, iClientInterface);
    }

    /**
     * Setup wifi native for AP mode operations.
     *
     * 1. Starts the Wifi HAL and configures it in AP mode.
     * 2. Setup Wificond to operate in AP mode and retrieve the handle to use for ap operations.
     *
     * @return Pair of <Integer, IApInterface> to indicate the status and the associated wificond
     * AP interface binder handler (will be null on failure).
     */
    public Pair<Integer, IApInterface> setupForSoftApMode() {
        if (!startHalIfNecessary(false)) {
            Log.e(mTAG, "Failed to start HAL for AP mode");
            return Pair.create(SETUP_FAILURE_HAL, null);
        }
        IApInterface iApInterface = mWificondControl.setupDriverForSoftApMode();
        if (iApInterface == null) {
            return Pair.create(SETUP_FAILURE_WIFICOND, null);
        }
        return Pair.create(SETUP_SUCCESS, iApInterface);
    }

    /**
     * Teardown all mode configurations in wifi native.
     *
     * 1. Stops the Wifi HAL.
     * 2. Tears down all the interfaces from Wificond.
     */
    public void tearDown() {
        stopHalIfNecessary();
        if (!mWificondControl.tearDownInterfaces()) {
            // TODO(b/34859006): Handle failures.
            Log.e(mTAG, "Failed to teardown interfaces from Wificond");
        }
    }

    /********************************************************
     * Wificond operations
     ********************************************************/
    /**
     * Result of a signal poll.
     */
    public static class SignalPollResult {
        // RSSI value in dBM.
        public int currentRssi;
        //Transmission bit rate in Mbps.
        public int txBitrate;
        // Association frequency in MHz.
        public int associationFrequency;
    }

    /**
     * WiFi interface transimission counters.
     */
    public static class TxPacketCounters {
        // Number of successfully transmitted packets.
        public int txSucceeded;
        // Number of tramsmission failures.
        public int txFailed;
    }

    /**
    * Disable wpa_supplicant via wificond.
    * @return Returns true on success.
    */
    public boolean disableSupplicant() {
        return mWificondControl.disableSupplicant();
    }

    /**
    * Enable wpa_supplicant via wificond.
    * @return Returns true on success.
    */
    public boolean enableSupplicant() {
        return mWificondControl.enableSupplicant();
    }

    /**
    * Request signal polling to wificond.
    * Returns an SignalPollResult object.
    * Returns null on failure.
    */
    public SignalPollResult signalPoll() {
        return mWificondControl.signalPoll();
    }

    /**
     * Fetch TX packet counters on current connection from wificond.
    * Returns an TxPacketCounters object.
    * Returns null on failure.
    */
    public TxPacketCounters getTxPacketCounters() {
        return mWificondControl.getTxPacketCounters();
    }

    /**
     * Start a scan using wificond for the given parameters.
     * @param freqs list of frequencies to scan for, if null scan all supported channels.
     * @param hiddenNetworkSSIDs List of hidden networks to be scanned for.
     * @return Returns true on success.
     */
    public boolean scan(Set<Integer> freqs, Set<String> hiddenNetworkSSIDs) {
        return mWificondControl.scan(freqs, hiddenNetworkSSIDs);
    }

    /**
     * Fetch the latest scan result from kernel via wificond.
     * @return Returns an ArrayList of ScanDetail.
     * Returns an empty ArrayList on failure.
     */
    public ArrayList<ScanDetail> getScanResults() {
        return mWificondControl.getScanResults(WificondControl.SCAN_TYPE_SINGLE_SCAN);
    }

    /**
     * Fetch the latest scan result from kernel via wificond.
     * @return Returns an ArrayList of ScanDetail.
     * Returns an empty ArrayList on failure.
     */
    public ArrayList<ScanDetail> getPnoScanResults() {
        return mWificondControl.getScanResults(WificondControl.SCAN_TYPE_PNO_SCAN);
    }

    /**
     * Start PNO scan.
     * @param pnoSettings Pno scan configuration.
     * @return true on success.
     */
    public boolean startPnoScan(PnoSettings pnoSettings) {
        return mWificondControl.startPnoScan(pnoSettings);
    }

    /**
     * Stop PNO scan.
     * @return true on success.
     */
    public boolean stopPnoScan() {
        return mWificondControl.stopPnoScan();
    }

    /********************************************************
     * Supplicant operations
     ********************************************************/

    /**
     * This method is called repeatedly until the connection to wpa_supplicant is established.
     *
     * @return true if connection is established, false otherwise.
     * TODO: Add unit tests for these once we remove the legacy code.
     */
    public boolean connectToSupplicant() {
        // Start initialization if not already started.
        if (!mSupplicantStaIfaceHal.isInitializationStarted()
                && !mSupplicantStaIfaceHal.initialize()) {
            return false;
        }
        // Check if the initialization is complete.
        return mSupplicantStaIfaceHal.isInitializationComplete();
    }

    /**
     * Close supplicant connection.
     */
    public void closeSupplicantConnection() {
        // Nothing to do for HIDL.
    }

    /**
     * Set supplicant log level
     *
     * @param turnOnVerbose Whether to turn on verbose logging or not.
     */
    public void setSupplicantLogLevel(boolean turnOnVerbose) {
        mSupplicantStaIfaceHal.setLogLevel(turnOnVerbose);
    }

    /**
     * Trigger a reconnection if the iface is disconnected.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean reconnect() {
        return mSupplicantStaIfaceHal.reconnect();
    }

    /**
     * Trigger a reassociation even if the iface is currently connected.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean reassociate() {
        return mSupplicantStaIfaceHal.reassociate();
    }

    /**
     * Trigger a disconnection from the currently connected network.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean disconnect() {
        return mSupplicantStaIfaceHal.disconnect();
    }

    /**
     * Makes a callback to HIDL to getMacAddress from supplicant
     *
     * @return string containing the MAC address, or null on a failed call
     */
    public String getMacAddress() {
        return mSupplicantStaIfaceHal.getMacAddress();
    }

    public static final int RX_FILTER_TYPE_V4_MULTICAST = 0;
    public static final int RX_FILTER_TYPE_V6_MULTICAST = 1;
    /**
     * Start filtering out Multicast V4 packets
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     *
     * Multicast filtering rules work as follows:
     *
     * The driver can filter multicast (v4 and/or v6) and broadcast packets when in
     * a power optimized mode (typically when screen goes off).
     *
     * In order to prevent the driver from filtering the multicast/broadcast packets, we have to
     * add a DRIVER RXFILTER-ADD rule followed by DRIVER RXFILTER-START to make the rule effective
     *
     * DRIVER RXFILTER-ADD Num
     *   where Num = 0 - Unicast, 1 - Broadcast, 2 - Mutil4 or 3 - Multi6
     *
     * and DRIVER RXFILTER-START
     * In order to stop the usage of these rules, we do
     *
     * DRIVER RXFILTER-STOP
     * DRIVER RXFILTER-REMOVE Num
     *   where Num is as described for RXFILTER-ADD
     *
     * The  SETSUSPENDOPT driver command overrides the filtering rules
     */
    public boolean startFilteringMulticastV4Packets() {
        return mSupplicantStaIfaceHal.stopRxFilter()
                && mSupplicantStaIfaceHal.removeRxFilter(
                RX_FILTER_TYPE_V4_MULTICAST)
                && mSupplicantStaIfaceHal.startRxFilter();
    }

    /**
     * Stop filtering out Multicast V4 packets.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean stopFilteringMulticastV4Packets() {
        return mSupplicantStaIfaceHal.stopRxFilter()
                && mSupplicantStaIfaceHal.addRxFilter(
                RX_FILTER_TYPE_V4_MULTICAST)
                && mSupplicantStaIfaceHal.startRxFilter();
    }

    /**
     * Start filtering out Multicast V6 packets
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean startFilteringMulticastV6Packets() {
        return mSupplicantStaIfaceHal.stopRxFilter()
                && mSupplicantStaIfaceHal.removeRxFilter(
                RX_FILTER_TYPE_V6_MULTICAST)
                && mSupplicantStaIfaceHal.startRxFilter();
    }

    /**
     * Stop filtering out Multicast V6 packets.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean stopFilteringMulticastV6Packets() {
        return mSupplicantStaIfaceHal.stopRxFilter()
                && mSupplicantStaIfaceHal.addRxFilter(
                RX_FILTER_TYPE_V6_MULTICAST)
                && mSupplicantStaIfaceHal.startRxFilter();
    }

    public static final int BLUETOOTH_COEXISTENCE_MODE_ENABLED  = 0;
    public static final int BLUETOOTH_COEXISTENCE_MODE_DISABLED = 1;
    public static final int BLUETOOTH_COEXISTENCE_MODE_SENSE    = 2;
    /**
      * Sets the bluetooth coexistence mode.
      *
      * @param mode One of {@link #BLUETOOTH_COEXISTENCE_MODE_DISABLED},
      *            {@link #BLUETOOTH_COEXISTENCE_MODE_ENABLED}, or
      *            {@link #BLUETOOTH_COEXISTENCE_MODE_SENSE}.
      * @return Whether the mode was successfully set.
      */
    public boolean setBluetoothCoexistenceMode(int mode) {
        return mSupplicantStaIfaceHal.setBtCoexistenceMode(mode);
    }

    /**
     * Enable or disable Bluetooth coexistence scan mode. When this mode is on,
     * some of the low-level scan parameters used by the driver are changed to
     * reduce interference with A2DP streaming.
     *
     * @param setCoexScanMode whether to enable or disable this mode
     * @return {@code true} if the command succeeded, {@code false} otherwise.
     */
    public boolean setBluetoothCoexistenceScanMode(boolean setCoexScanMode) {
        return mSupplicantStaIfaceHal.setBtCoexistenceScanModeEnabled(setCoexScanMode);
    }

    /**
     * Enable or disable suspend mode optimizations.
     *
     * @param enabled true to enable, false otherwise.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setSuspendOptimizations(boolean enabled) {
        return mSupplicantStaIfaceHal.setSuspendModeEnabled(enabled);
    }

    /**
     * Set country code.
     *
     * @param countryCode 2 byte ASCII string. For ex: US, CA.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setCountryCode(String countryCode) {
        return mSupplicantStaIfaceHal.setCountryCode(countryCode);
    }

    /**
     * Initiate TDLS discover and setup or teardown with the specified peer.
     *
     * @param macAddr MAC Address of the peer.
     * @param enable true to start discovery and setup, false to teardown.
     */
    public void startTdls(String macAddr, boolean enable) {
        if (enable) {
            mSupplicantStaIfaceHal.initiateTdlsDiscover(macAddr);
            mSupplicantStaIfaceHal.initiateTdlsSetup(macAddr);
        } else {
            mSupplicantStaIfaceHal.initiateTdlsTeardown(macAddr);
        }
    }

    /**
     * Start WPS pin display operation with the specified peer.
     *
     * @param bssid BSSID of the peer.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startWpsPbc(String bssid) {
        return mSupplicantStaIfaceHal.startWpsPbc(bssid);
    }

    /**
     * Start WPS pin keypad operation with the specified pin.
     *
     * @param pin Pin to be used.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startWpsPinKeypad(String pin) {
        return mSupplicantStaIfaceHal.startWpsPinKeypad(pin);
    }

    /**
     * Start WPS pin display operation with the specified peer.
     *
     * @param bssid BSSID of the peer.
     * @return new pin generated on success, null otherwise.
     */
    public String startWpsPinDisplay(String bssid) {
        return mSupplicantStaIfaceHal.startWpsPinDisplay(bssid);
    }

    /**
     * Sets whether to use external sim for SIM/USIM processing.
     *
     * @param external true to enable, false otherwise.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setExternalSim(boolean external) {
        return mSupplicantStaIfaceHal.setExternalSim(external);
    }

    /**
     * Sim auth response types.
     */
    public static final String SIM_AUTH_RESP_TYPE_GSM_AUTH = "GSM-AUTH";
    public static final String SIM_AUTH_RESP_TYPE_UMTS_AUTH = "UMTS-AUTH";
    public static final String SIM_AUTH_RESP_TYPE_UMTS_AUTS = "UMTS-AUTS";

    /**
     * Send the sim auth response for the currently configured network.
     *
     * @param type |GSM-AUTH|, |UMTS-AUTH| or |UMTS-AUTS|.
     * @param response Response params.
     * @return true if succeeds, false otherwise.
     */
    public boolean simAuthResponse(int id, String type, String response) {
        if (SIM_AUTH_RESP_TYPE_GSM_AUTH.equals(type)) {
            return mSupplicantStaIfaceHal.sendCurrentNetworkEapSimGsmAuthResponse(response);
        } else if (SIM_AUTH_RESP_TYPE_UMTS_AUTH.equals(type)) {
            return mSupplicantStaIfaceHal.sendCurrentNetworkEapSimUmtsAuthResponse(response);
        } else if (SIM_AUTH_RESP_TYPE_UMTS_AUTS.equals(type)) {
            return mSupplicantStaIfaceHal.sendCurrentNetworkEapSimUmtsAutsResponse(response);
        } else {
            return false;
        }
    }

    /**
     * Send the eap sim gsm auth failure for the currently configured network.
     *
     * @return true if succeeds, false otherwise.
     */
    public boolean simAuthFailedResponse(int id) {
        return mSupplicantStaIfaceHal.sendCurrentNetworkEapSimGsmAuthFailure();
    }

    /**
     * Send the eap sim umts auth failure for the currently configured network.
     *
     * @return true if succeeds, false otherwise.
     */
    public boolean umtsAuthFailedResponse(int id) {
        return mSupplicantStaIfaceHal.sendCurrentNetworkEapSimUmtsAuthFailure();
    }

    /**
     * Send the eap identity response for the currently configured network.
     *
     * @param response String to send.
     * @return true if succeeds, false otherwise.
     */
    public boolean simIdentityResponse(int id, String response) {
        return mSupplicantStaIfaceHal.sendCurrentNetworkEapIdentityResponse(response);
    }

    /**
     * This get anonymous identity from supplicant and returns it as a string.
     *
     * @return anonymous identity string if succeeds, null otherwise.
     */
    public String getEapAnonymousIdentity() {
        return mSupplicantStaIfaceHal.getCurrentNetworkEapAnonymousIdentity();
    }

    /**
     * Start WPS pin registrar operation with the specified peer and pin.
     *
     * @param bssid BSSID of the peer.
     * @param pin Pin to be used.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean startWpsRegistrar(String bssid, String pin) {
        return mSupplicantStaIfaceHal.startWpsRegistrar(bssid, pin);
    }

    /**
     * Cancels any ongoing WPS requests.
     *
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean cancelWps() {
        return mSupplicantStaIfaceHal.cancelWps();
    }

    /**
     * Set WPS device name.
     *
     * @param name String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setDeviceName(String name) {
        return mSupplicantStaIfaceHal.setWpsDeviceName(name);
    }

    /**
     * Set WPS device type.
     *
     * @param type Type specified as a string. Used format: <categ>-<OUI>-<subcateg>
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setDeviceType(String type) {
        return mSupplicantStaIfaceHal.setWpsDeviceType(type);
    }

    /**
     * Set WPS config methods
     *
     * @param cfg List of config methods.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setConfigMethods(String cfg) {
        return mSupplicantStaIfaceHal.setWpsConfigMethods(cfg);
    }

    /**
     * Set WPS manufacturer.
     *
     * @param value String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setManufacturer(String value) {
        return mSupplicantStaIfaceHal.setWpsManufacturer(value);
    }

    /**
     * Set WPS model name.
     *
     * @param value String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setModelName(String value) {
        return mSupplicantStaIfaceHal.setWpsModelName(value);
    }

    /**
     * Set WPS model number.
     *
     * @param value String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setModelNumber(String value) {
        return mSupplicantStaIfaceHal.setWpsModelNumber(value);
    }

    /**
     * Set WPS serial number.
     *
     * @param value String to be set.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setSerialNumber(String value) {
        return mSupplicantStaIfaceHal.setWpsSerialNumber(value);
    }

    /**
     * Enable or disable power save mode.
     *
     * @param enabled true to enable, false to disable.
     */
    public void setPowerSave(boolean enabled) {
        mSupplicantStaIfaceHal.setPowerSave(enabled);
    }

    /**
     * Set concurrency priority between P2P & STA operations.
     *
     * @param isStaHigherPriority Set to true to prefer STA over P2P during concurrency operations,
     *                            false otherwise.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean setConcurrencyPriority(boolean isStaHigherPriority) {
        return mSupplicantStaIfaceHal.setConcurrencyPriority(isStaHigherPriority);
    }

    /**
     * Enable/Disable auto reconnect functionality in wpa_supplicant.
     *
     * @param enable true to enable auto reconnecting, false to disable.
     * @return true if request is sent successfully, false otherwise.
     */
    public boolean enableStaAutoReconnect(boolean enable) {
        return mSupplicantStaIfaceHal.enableAutoReconnect(enable);
    }

    /**
     * Migrate all the configured networks from wpa_supplicant.
     *
     * @param configs       Map of configuration key to configuration objects corresponding to all
     *                      the networks.
     * @param networkExtras Map of extra configuration parameters stored in wpa_supplicant.conf
     * @return Max priority of all the configs.
     */
    public boolean migrateNetworksFromSupplicant(Map<String, WifiConfiguration> configs,
                                                 SparseArray<Map<String, String>> networkExtras) {
        return mSupplicantStaIfaceHal.loadNetworks(configs, networkExtras);
    }

    /**
     * Add the provided network configuration to wpa_supplicant and initiate connection to it.
     * This method does the following:
     * 1. Abort any ongoing scan to unblock the connection request.
     * 2. Remove any existing network in wpa_supplicant(This implicitly triggers disconnect).
     * 3. Add a new network to wpa_supplicant.
     * 4. Save the provided configuration to wpa_supplicant.
     * 5. Select the new network in wpa_supplicant.
     * 6. Triggers reconnect command to wpa_supplicant.
     *
     * @param configuration WifiConfiguration parameters for the provided network.
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean connectToNetwork(WifiConfiguration configuration) {
        // Abort ongoing scan before connect() to unblock connection request.
        mWificondControl.abortScan();
        return mSupplicantStaIfaceHal.connectToNetwork(configuration);
    }

    /**
     * Initiates roaming to the already configured network in wpa_supplicant. If the network
     * configuration provided does not match the already configured network, then this triggers
     * a new connection attempt (instead of roam).
     * 1. Abort any ongoing scan to unblock the roam request.
     * 2. First check if we're attempting to connect to the same network as we currently have
     * configured.
     * 3. Set the new bssid for the network in wpa_supplicant.
     * 4. Triggers reassociate command to wpa_supplicant.
     *
     * @param configuration WifiConfiguration parameters for the provided network.
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean roamToNetwork(WifiConfiguration configuration) {
        // Abort ongoing scan before connect() to unblock roaming request.
        mWificondControl.abortScan();
        return mSupplicantStaIfaceHal.roamToNetwork(configuration);
    }

    /**
     * Get the framework network ID corresponding to the provided supplicant network ID for the
     * network configured in wpa_supplicant.
     *
     * @param supplicantNetworkId network ID in wpa_supplicant for the network.
     * @return Corresponding framework network ID if found, -1 if network not found.
     */
    public int getFrameworkNetworkId(int supplicantNetworkId) {
        return supplicantNetworkId;
    }

    /**
     * Remove all the networks.
     *
     * @return {@code true} if it succeeds, {@code false} otherwise
     */
    public boolean removeAllNetworks() {
        return mSupplicantStaIfaceHal.removeAllNetworks();
    }

    /**
     * Set the BSSID for the currently configured network in wpa_supplicant.
     *
     * @return true if successful, false otherwise.
     */
    public boolean setConfiguredNetworkBSSID(String bssid) {
        return mSupplicantStaIfaceHal.setCurrentNetworkBssid(bssid);
    }

    /**
     * Initiate ANQP query.
     *
     * @param bssid BSSID of the AP to be queried
     * @param anqpIds Set of anqp IDs.
     * @param hs20Subtypes Set of HS20 subtypes.
     * @return true on success, false otherwise.
     */
    public boolean requestAnqp(String bssid, Set<Integer> anqpIds, Set<Integer> hs20Subtypes) {
        if (bssid == null || ((anqpIds == null || anqpIds.isEmpty())
                && (hs20Subtypes == null || hs20Subtypes.isEmpty()))) {
            Log.e(mTAG, "Invalid arguments for ANQP request.");
            return false;
        }
        ArrayList<Short> anqpIdList = new ArrayList<>();
        for (Integer anqpId : anqpIds) {
            anqpIdList.add(anqpId.shortValue());
        }
        ArrayList<Integer> hs20SubtypeList = new ArrayList<>();
        hs20SubtypeList.addAll(hs20Subtypes);
        return mSupplicantStaIfaceHal.initiateAnqpQuery(bssid, anqpIdList, hs20SubtypeList);
    }

    /**
     * Request a passpoint icon file |filename| from the specified AP |bssid|.
     * @param bssid BSSID of the AP
     * @param fileName name of the icon file
     * @return true if request is sent successfully, false otherwise
     */
    public boolean requestIcon(String  bssid, String fileName) {
        if (bssid == null || fileName == null) {
            Log.e(mTAG, "Invalid arguments for Icon request.");
            return false;
        }
        return mSupplicantStaIfaceHal.initiateHs20IconQuery(bssid, fileName);
    }

    /**
     * Get the currently configured network's WPS NFC token.
     *
     * @return Hex string corresponding to the WPS NFC token.
     */
    public String getCurrentNetworkWpsNfcConfigurationToken() {
        return mSupplicantStaIfaceHal.getCurrentNetworkWpsNfcConfigurationToken();
    }

    /** Remove the request |networkId| from supplicant if it's the current network,
     * if the current configured network matches |networkId|.
     *
     * @param networkId network id of the network to be removed from supplicant.
     */
    public void removeNetworkIfCurrent(int networkId) {
        mSupplicantStaIfaceHal.removeNetworkIfCurrent(networkId);
    }

    /********************************************************
     * Vendor HAL operations
     ********************************************************/
    /**
     * Callback to notify vendor HAL death.
     */
    public interface VendorHalDeathEventHandler {
        /**
         * Invoked when the vendor HAL dies.
         */
        void onDeath();
    }

    /**
     * Initializes the vendor HAL. This is just used to initialize the {@link HalDeviceManager}.
     */
    public boolean initializeVendorHal(VendorHalDeathEventHandler handler) {
        return mWifiVendorHal.initialize(handler);
    }

    /**
     * Bring up the Vendor HAL and configure for STA mode or AP mode, if vendor HAL is supported.
     *
     * @param isStaMode true to start HAL in STA mode, false to start in AP mode.
     * @return false if the HAL start fails, true if successful or if vendor HAL not supported.
     */
    private boolean startHalIfNecessary(boolean isStaMode) {
        if (!mWifiVendorHal.isVendorHalSupported()) {
            Log.i(mTAG, "Vendor HAL not supported, Ignore start...");
            return true;
        }
        return mWifiVendorHal.startVendorHal(isStaMode);
    }

    /**
     * Stops the HAL, if vendor HAL is supported.
     */
    private void stopHalIfNecessary() {
        if (!mWifiVendorHal.isVendorHalSupported()) {
            Log.i(mTAG, "Vendor HAL not supported, Ignore stop...");
            return;
        }
        mWifiVendorHal.stopVendorHal();
    }

    /**
     * Tests whether the HAL is running or not
     */
    public boolean isHalStarted() {
        return mWifiVendorHal.isHalStarted();
    }

    // TODO: Change variable names to camel style.
    public static class ScanCapabilities {
        public int  max_scan_cache_size;
        public int  max_scan_buckets;
        public int  max_ap_cache_per_scan;
        public int  max_rssi_sample_size;
        public int  max_scan_reporting_threshold;
    }

    /**
     * Gets the scan capabilities
     *
     * @param capabilities object to be filled in
     * @return true for success. false for failure
     */
    public boolean getBgScanCapabilities(ScanCapabilities capabilities) {
        return mWifiVendorHal.getBgScanCapabilities(capabilities);
    }

    public static class ChannelSettings {
        public int frequency;
        public int dwell_time_ms;
        public boolean passive;
    }

    public static class BucketSettings {
        public int bucket;
        public int band;
        public int period_ms;
        public int max_period_ms;
        public int step_count;
        public int report_events;
        public int num_channels;
        public ChannelSettings[] channels;
    }

    /**
     * Network parameters for hidden networks to be scanned for.
     */
    public static class HiddenNetwork {
        public String ssid;

        @Override
        public boolean equals(Object otherObj) {
            if (this == otherObj) {
                return true;
            } else if (otherObj == null || getClass() != otherObj.getClass()) {
                return false;
            }
            HiddenNetwork other = (HiddenNetwork) otherObj;
            return Objects.equals(ssid, other.ssid);
        }

        @Override
        public int hashCode() {
            return (ssid == null ? 0 : ssid.hashCode());
        }
    }

    public static class ScanSettings {
        public int base_period_ms;
        public int max_ap_per_scan;
        public int report_threshold_percent;
        public int report_threshold_num_scans;
        public int num_buckets;
        /* Not used for bg scans. Only works for single scans. */
        public HiddenNetwork[] hiddenNetworks;
        public BucketSettings[] buckets;
    }

    /**
     * Network parameters to start PNO scan.
     */
    public static class PnoNetwork {
        public String ssid;
        public byte flags;
        public byte auth_bit_field;

        @Override
        public boolean equals(Object otherObj) {
            if (this == otherObj) {
                return true;
            } else if (otherObj == null || getClass() != otherObj.getClass()) {
                return false;
            }
            PnoNetwork other = (PnoNetwork) otherObj;
            return ((Objects.equals(ssid, other.ssid)) && (flags == other.flags)
                    && (auth_bit_field == other.auth_bit_field));
        }

        @Override
        public int hashCode() {
            int result = (ssid == null ? 0 : ssid.hashCode());
            result ^= ((int) flags * 31) + ((int) auth_bit_field << 8);
            return result;
        }
    }

    /**
     * Parameters to start PNO scan. This holds the list of networks which are going to used for
     * PNO scan.
     */
    public static class PnoSettings {
        public int min5GHzRssi;
        public int min24GHzRssi;
        public int initialScoreMax;
        public int currentConnectionBonus;
        public int sameNetworkBonus;
        public int secureBonus;
        public int band5GHzBonus;
        public int periodInMs;
        public boolean isConnected;
        public PnoNetwork[] networkList;
    }

    public static interface ScanEventHandler {
        /**
         * Called for each AP as it is found with the entire contents of the beacon/probe response.
         * Only called when WifiScanner.REPORT_EVENT_FULL_SCAN_RESULT is specified.
         */
        void onFullScanResult(ScanResult fullScanResult, int bucketsScanned);
        /**
         * Callback on an event during a gscan scan.
         * See WifiNative.WIFI_SCAN_* for possible values.
         */
        void onScanStatus(int event);
        /**
         * Called with the current cached scan results when gscan is paused.
         */
        void onScanPaused(WifiScanner.ScanData[] data);
        /**
         * Called with the current cached scan results when gscan is resumed.
         */
        void onScanRestarted();
    }

    /**
     * Handler to notify the occurrence of various events during PNO scan.
     */
    public interface PnoEventHandler {
        /**
         * Callback to notify when one of the shortlisted networks is found during PNO scan.
         * @param results List of Scan results received.
         */
        void onPnoNetworkFound(ScanResult[] results);

        /**
         * Callback to notify when the PNO scan schedule fails.
         */
        void onPnoScanFailed();
    }

    public static final int WIFI_SCAN_RESULTS_AVAILABLE = 0;
    public static final int WIFI_SCAN_THRESHOLD_NUM_SCANS = 1;
    public static final int WIFI_SCAN_THRESHOLD_PERCENT = 2;
    public static final int WIFI_SCAN_FAILED = 3;

    /**
     * Starts a background scan.
     * Any ongoing scan will be stopped first
     *
     * @param settings     to control the scan
     * @param eventHandler to call with the results
     * @return true for success
     */
    public boolean startBgScan(ScanSettings settings, ScanEventHandler eventHandler) {
        return mWifiVendorHal.startBgScan(settings, eventHandler);
    }

    /**
     * Stops any ongoing backgound scan
     */
    public void stopBgScan() {
        mWifiVendorHal.stopBgScan();
    }

    /**
     * Pauses an ongoing backgound scan
     */
    public void pauseBgScan() {
        mWifiVendorHal.pauseBgScan();
    }

    /**
     * Restarts a paused scan
     */
    public void restartBgScan() {
        mWifiVendorHal.restartBgScan();
    }

    /**
     * Gets the latest scan results received.
     */
    public WifiScanner.ScanData[] getBgScanResults() {
        return mWifiVendorHal.getBgScanResults();
    }

    public WifiLinkLayerStats getWifiLinkLayerStats(String iface) {
        return mWifiVendorHal.getWifiLinkLayerStats();
    }

    /**
     * Get the supported features
     *
     * @return bitmask defined by WifiManager.WIFI_FEATURE_*
     */
    public int getSupportedFeatureSet() {
        return mWifiVendorHal.getSupportedFeatureSet();
    }

    public static interface RttEventHandler {
        void onRttResults(RttManager.RttResult[] result);
    }

    /**
     * Starts a new rtt request
     *
     * @param params RTT request params. Refer to {@link RttManager#RttParams}.
     * @param handler Callback to be invoked to notify any results.
     * @return true if the request was successful, false otherwise.
     */
    public boolean requestRtt(
            RttManager.RttParams[] params, RttEventHandler handler) {
        return mWifiVendorHal.requestRtt(params, handler);
    }

    /**
     * Cancels an outstanding rtt request
     *
     * @param params RTT request params. Refer to {@link RttManager#RttParams}
     * @return true if there was an outstanding request and it was successfully cancelled
     */
    public boolean cancelRtt(RttManager.RttParams[] params) {
        return mWifiVendorHal.cancelRtt(params);
    }

    /**
     * Enable RTT responder role on the device. Returns {@link ResponderConfig} if the responder
     * role is successfully enabled, {@code null} otherwise.
     *
     * @param timeoutSeconds timeout to use for the responder.
     */
    @Nullable
    public ResponderConfig enableRttResponder(int timeoutSeconds) {
        return mWifiVendorHal.enableRttResponder(timeoutSeconds);
    }

    /**
     * Disable RTT responder role. Returns {@code true} if responder role is successfully disabled,
     * {@code false} otherwise.
     */
    public boolean disableRttResponder() {
        return mWifiVendorHal.disableRttResponder();
    }

    /**
     * Set the MAC OUI during scanning.
     * An OUI {Organizationally Unique Identifier} is a 24-bit number that
     * uniquely identifies a vendor or manufacturer.
     *
     * @param oui OUI to set.
     * @return true for success
     */
    public boolean setScanningMacOui(byte[] oui) {
        return mWifiVendorHal.setScanningMacOui(oui);
    }

    /**
     * Query the list of valid frequencies for the provided band.
     * The result depends on the on the country code that has been set.
     *
     * @param band as specified by one of the WifiScanner.WIFI_BAND_* constants.
     * @return frequencies vector of valid frequencies (MHz), or null for error.
     * @throws IllegalArgumentException if band is not recognized.
     */
    public int [] getChannelsForBand(int band) {
        return mWifiVendorHal.getChannelsForBand(band);
    }

    /**
     * Indicates whether getChannelsForBand is supported.
     *
     * @return true if it is.
     */
    public boolean isGetChannelsForBandSupported() {
        return mWifiVendorHal.isGetChannelsForBandSupported();
    }

    /**
     * RTT (Round Trip Time) measurement capabilities of the device.
     */
    public RttManager.RttCapabilities getRttCapabilities() {
        return mWifiVendorHal.getRttCapabilities();
    }

    /**
     * Get the APF (Android Packet Filter) capabilities of the device
     */
    public ApfCapabilities getApfCapabilities() {
        return mWifiVendorHal.getApfCapabilities();
    }

    /**
     * Installs an APF program on this iface, replacing any existing program.
     *
     * @param filter is the android packet filter program
     * @return true for success
     */
    public boolean installPacketFilter(byte[] filter) {
        return mWifiVendorHal.installPacketFilter(filter);
    }

    /**
     * Set country code for this AP iface.
     *
     * @param countryCode - two-letter country code (as ISO 3166)
     * @return true for success
     */
    public boolean setCountryCodeHal(String countryCode) {
        return mWifiVendorHal.setCountryCodeHal(countryCode);
    }

    //---------------------------------------------------------------------------------
    /* Wifi Logger commands/events */
    public static interface WifiLoggerEventHandler {
        void onRingBufferData(RingBufferStatus status, byte[] buffer);
        void onWifiAlert(int errorCode, byte[] buffer);
    }

    /**
     * Registers the logger callback and enables alerts.
     * Ring buffer data collection is only triggered when |startLoggingRingBuffer| is invoked.
     *
     * @param handler Callback to be invoked.
     * @return true on success, false otherwise.
     */
    public boolean setLoggingEventHandler(WifiLoggerEventHandler handler) {
        return mWifiVendorHal.setLoggingEventHandler(handler);
    }

    /**
     * Control debug data collection
     *
     * @param verboseLevel 0 to 3, inclusive. 0 stops logging.
     * @param flags        Ignored.
     * @param maxInterval  Maximum interval between reports; ignore if 0.
     * @param minDataSize  Minimum data size in buffer for report; ignore if 0.
     * @param ringName     Name of the ring for which data collection is to start.
     * @return true for success, false otherwise.
     */
    public boolean startLoggingRingBuffer(int verboseLevel, int flags, int maxInterval,
            int minDataSize, String ringName){
        return mWifiVendorHal.startLoggingRingBuffer(
                verboseLevel, flags, maxInterval, minDataSize, ringName);
    }

    /**
     * Logger features exposed.
     * This is a no-op now, will always return -1.
     *
     * @return true on success, false otherwise.
     */
    public int getSupportedLoggerFeatureSet() {
        return mWifiVendorHal.getSupportedLoggerFeatureSet();
    }

    /**
     * Stops all logging and resets the logger callback.
     * This stops both the alerts and ring buffer data collection.
     * @return true on success, false otherwise.
     */
    public boolean resetLogHandler() {
        return mWifiVendorHal.resetLogHandler();
    }

    /**
     * Vendor-provided wifi driver version string
     *
     * @return String returned from the HAL.
     */
    public String getDriverVersion() {
        return mWifiVendorHal.getDriverVersion();
    }

    /**
     * Vendor-provided wifi firmware version string
     *
     * @return String returned from the HAL.
     */
    public String getFirmwareVersion() {
        return mWifiVendorHal.getFirmwareVersion();
    }

    public static class RingBufferStatus{
        String name;
        int flag;
        int ringBufferId;
        int ringBufferByteSize;
        int verboseLevel;
        int writtenBytes;
        int readBytes;
        int writtenRecords;

        // Bit masks for interpreting |flag|
        public static final int HAS_BINARY_ENTRIES = (1 << 0);
        public static final int HAS_ASCII_ENTRIES = (1 << 1);
        public static final int HAS_PER_PACKET_ENTRIES = (1 << 2);

        @Override
        public String toString() {
            return "name: " + name + " flag: " + flag + " ringBufferId: " + ringBufferId +
                    " ringBufferByteSize: " +ringBufferByteSize + " verboseLevel: " +verboseLevel +
                    " writtenBytes: " + writtenBytes + " readBytes: " + readBytes +
                    " writtenRecords: " + writtenRecords;
        }
    }

    /**
     * API to get the status of all ring buffers supported by driver
     */
    public RingBufferStatus[] getRingBufferStatus() {
        return mWifiVendorHal.getRingBufferStatus();
    }

    /**
     * Indicates to driver that all the data has to be uploaded urgently
     *
     * @param ringName Name of the ring buffer requested.
     * @return true on success, false otherwise.
     */
    public boolean getRingBufferData(String ringName) {
        return mWifiVendorHal.getRingBufferData(ringName);
    }

    /**
     * Request vendor debug info from the firmware
     *
     * @return Raw data obtained from the HAL.
     */
    public byte[] getFwMemoryDump() {
        return mWifiVendorHal.getFwMemoryDump();
    }

    /**
     * Request vendor debug info from the driver
     *
     * @return Raw data obtained from the HAL.
     */
    public byte[] getDriverStateDump() {
        return mWifiVendorHal.getDriverStateDump();
    }

    //---------------------------------------------------------------------------------
    /* Packet fate API */

    @Immutable
    abstract static class FateReport {
        final static int USEC_PER_MSEC = 1000;
        // The driver timestamp is a 32-bit counter, in microseconds. This field holds the
        // maximal value of a driver timestamp in milliseconds.
        final static int MAX_DRIVER_TIMESTAMP_MSEC = (int) (0xffffffffL / 1000);
        final static SimpleDateFormat dateFormatter = new SimpleDateFormat("HH:mm:ss.SSS");

        final byte mFate;
        final long mDriverTimestampUSec;
        final byte mFrameType;
        final byte[] mFrameBytes;
        final long mEstimatedWallclockMSec;

        FateReport(byte fate, long driverTimestampUSec, byte frameType, byte[] frameBytes) {
            mFate = fate;
            mDriverTimestampUSec = driverTimestampUSec;
            mEstimatedWallclockMSec =
                    convertDriverTimestampUSecToWallclockMSec(mDriverTimestampUSec);
            mFrameType = frameType;
            mFrameBytes = frameBytes;
        }

        public String toTableRowString() {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            FrameParser parser = new FrameParser(mFrameType, mFrameBytes);
            dateFormatter.setTimeZone(TimeZone.getDefault());
            pw.format("%-15s  %12s  %-9s  %-32s  %-12s  %-23s  %s\n",
                    mDriverTimestampUSec,
                    dateFormatter.format(new Date(mEstimatedWallclockMSec)),
                    directionToString(), fateToString(), parser.mMostSpecificProtocolString,
                    parser.mTypeString, parser.mResultString);
            return sw.toString();
        }

        public String toVerboseStringWithPiiAllowed() {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            FrameParser parser = new FrameParser(mFrameType, mFrameBytes);
            pw.format("Frame direction: %s\n", directionToString());
            pw.format("Frame timestamp: %d\n", mDriverTimestampUSec);
            pw.format("Frame fate: %s\n", fateToString());
            pw.format("Frame type: %s\n", frameTypeToString(mFrameType));
            pw.format("Frame protocol: %s\n", parser.mMostSpecificProtocolString);
            pw.format("Frame protocol type: %s\n", parser.mTypeString);
            pw.format("Frame length: %d\n", mFrameBytes.length);
            pw.append("Frame bytes");
            pw.append(HexDump.dumpHexString(mFrameBytes));  // potentially contains PII
            pw.append("\n");
            return sw.toString();
        }

        /* Returns a header to match the output of toTableRowString(). */
        public static String getTableHeader() {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.format("\n%-15s  %-12s  %-9s  %-32s  %-12s  %-23s  %s\n",
                    "Time usec", "Walltime", "Direction", "Fate", "Protocol", "Type", "Result");
            pw.format("%-15s  %-12s  %-9s  %-32s  %-12s  %-23s  %s\n",
                    "---------", "--------", "---------", "----", "--------", "----", "------");
            return sw.toString();
        }

        protected abstract String directionToString();

        protected abstract String fateToString();

        private static String frameTypeToString(byte frameType) {
            switch (frameType) {
                case WifiLoggerHal.FRAME_TYPE_UNKNOWN:
                    return "unknown";
                case WifiLoggerHal.FRAME_TYPE_ETHERNET_II:
                    return "data";
                case WifiLoggerHal.FRAME_TYPE_80211_MGMT:
                    return "802.11 management";
                default:
                    return Byte.toString(frameType);
            }
        }

        /**
         * Converts a driver timestamp to a wallclock time, based on the current
         * BOOTTIME to wallclock mapping. The driver timestamp is a 32-bit counter of
         * microseconds, with the same base as BOOTTIME.
         */
        private static long convertDriverTimestampUSecToWallclockMSec(long driverTimestampUSec) {
            final long wallclockMillisNow = System.currentTimeMillis();
            final long boottimeMillisNow = SystemClock.elapsedRealtime();
            final long driverTimestampMillis = driverTimestampUSec / USEC_PER_MSEC;

            long boottimeTimestampMillis = boottimeMillisNow % MAX_DRIVER_TIMESTAMP_MSEC;
            if (boottimeTimestampMillis < driverTimestampMillis) {
                // The 32-bit microsecond count has wrapped between the time that the driver
                // recorded the packet, and the call to this function. Adjust the BOOTTIME
                // timestamp, to compensate.
                //
                // Note that overflow is not a concern here, since the result is less than
                // 2 * MAX_DRIVER_TIMESTAMP_MSEC. (Given the modulus operation above,
                // boottimeTimestampMillis must be less than MAX_DRIVER_TIMESTAMP_MSEC.) And, since
                // MAX_DRIVER_TIMESTAMP_MSEC is an int, 2 * MAX_DRIVER_TIMESTAMP_MSEC must fit
                // within a long.
                boottimeTimestampMillis += MAX_DRIVER_TIMESTAMP_MSEC;
            }

            final long millisSincePacketTimestamp = boottimeTimestampMillis - driverTimestampMillis;
            return wallclockMillisNow - millisSincePacketTimestamp;
        }
    }

    /**
     * Represents the fate information for one outbound packet.
     */
    @Immutable
    public static final class TxFateReport extends FateReport {
        TxFateReport(byte fate, long driverTimestampUSec, byte frameType, byte[] frameBytes) {
            super(fate, driverTimestampUSec, frameType, frameBytes);
        }

        @Override
        protected String directionToString() {
            return "TX";
        }

        @Override
        protected String fateToString() {
            switch (mFate) {
                case WifiLoggerHal.TX_PKT_FATE_ACKED:
                    return "acked";
                case WifiLoggerHal.TX_PKT_FATE_SENT:
                    return "sent";
                case WifiLoggerHal.TX_PKT_FATE_FW_QUEUED:
                    return "firmware queued";
                case WifiLoggerHal.TX_PKT_FATE_FW_DROP_INVALID:
                    return "firmware dropped (invalid frame)";
                case WifiLoggerHal.TX_PKT_FATE_FW_DROP_NOBUFS:
                    return "firmware dropped (no bufs)";
                case WifiLoggerHal.TX_PKT_FATE_FW_DROP_OTHER:
                    return "firmware dropped (other)";
                case WifiLoggerHal.TX_PKT_FATE_DRV_QUEUED:
                    return "driver queued";
                case WifiLoggerHal.TX_PKT_FATE_DRV_DROP_INVALID:
                    return "driver dropped (invalid frame)";
                case WifiLoggerHal.TX_PKT_FATE_DRV_DROP_NOBUFS:
                    return "driver dropped (no bufs)";
                case WifiLoggerHal.TX_PKT_FATE_DRV_DROP_OTHER:
                    return "driver dropped (other)";
                default:
                    return Byte.toString(mFate);
            }
        }
    }

    /**
     * Represents the fate information for one inbound packet.
     */
    @Immutable
    public static final class RxFateReport extends FateReport {
        RxFateReport(byte fate, long driverTimestampUSec, byte frameType, byte[] frameBytes) {
            super(fate, driverTimestampUSec, frameType, frameBytes);
        }

        @Override
        protected String directionToString() {
            return "RX";
        }

        @Override
        protected String fateToString() {
            switch (mFate) {
                case WifiLoggerHal.RX_PKT_FATE_SUCCESS:
                    return "success";
                case WifiLoggerHal.RX_PKT_FATE_FW_QUEUED:
                    return "firmware queued";
                case WifiLoggerHal.RX_PKT_FATE_FW_DROP_FILTER:
                    return "firmware dropped (filter)";
                case WifiLoggerHal.RX_PKT_FATE_FW_DROP_INVALID:
                    return "firmware dropped (invalid frame)";
                case WifiLoggerHal.RX_PKT_FATE_FW_DROP_NOBUFS:
                    return "firmware dropped (no bufs)";
                case WifiLoggerHal.RX_PKT_FATE_FW_DROP_OTHER:
                    return "firmware dropped (other)";
                case WifiLoggerHal.RX_PKT_FATE_DRV_QUEUED:
                    return "driver queued";
                case WifiLoggerHal.RX_PKT_FATE_DRV_DROP_FILTER:
                    return "driver dropped (filter)";
                case WifiLoggerHal.RX_PKT_FATE_DRV_DROP_INVALID:
                    return "driver dropped (invalid frame)";
                case WifiLoggerHal.RX_PKT_FATE_DRV_DROP_NOBUFS:
                    return "driver dropped (no bufs)";
                case WifiLoggerHal.RX_PKT_FATE_DRV_DROP_OTHER:
                    return "driver dropped (other)";
                default:
                    return Byte.toString(mFate);
            }
        }
    }

    /**
     * Ask the HAL to enable packet fate monitoring. Fails unless HAL is started.
     *
     * @return true for success, false otherwise.
     */
    public boolean startPktFateMonitoring() {
        return mWifiVendorHal.startPktFateMonitoring();
    }

    /**
     * Fetch the most recent TX packet fates from the HAL. Fails unless HAL is started.
     *
     * @return true for success, false otherwise.
     */
    public boolean getTxPktFates(TxFateReport[] reportBufs) {
        return mWifiVendorHal.getTxPktFates(reportBufs);
    }

    /**
     * Fetch the most recent RX packet fates from the HAL. Fails unless HAL is started.
     */
    public boolean getRxPktFates(RxFateReport[] reportBufs) {
        return mWifiVendorHal.getRxPktFates(reportBufs);
    }

    /**
     * Start sending the specified keep alive packets periodically.
     *
     * @param slot Integer used to identify each request.
     * @param keepAlivePacket Raw packet contents to send.
     * @param period Period to use for sending these packets.
     * @return 0 for success, -1 for error
     */
    public int startSendingOffloadedPacket(int slot, KeepalivePacketData keepAlivePacket,
                                           int period) {
        String[] macAddrStr = getMacAddress().split(":");
        byte[] srcMac = new byte[6];
        for (int i = 0; i < 6; i++) {
            Integer hexVal = Integer.parseInt(macAddrStr[i], 16);
            srcMac[i] = hexVal.byteValue();
        }
        return mWifiVendorHal.startSendingOffloadedPacket(
                slot, srcMac, keepAlivePacket, period);
    }

    /**
     * Stop sending the specified keep alive packets.
     *
     * @param slot id - same as startSendingOffloadedPacket call.
     * @return 0 for success, -1 for error
     */
    public int stopSendingOffloadedPacket(int slot) {
        return mWifiVendorHal.stopSendingOffloadedPacket(slot);
    }

    public static interface WifiRssiEventHandler {
        void onRssiThresholdBreached(byte curRssi);
    }

    /**
     * Start RSSI monitoring on the currently connected access point.
     *
     * @param maxRssi          Maximum RSSI threshold.
     * @param minRssi          Minimum RSSI threshold.
     * @param rssiEventHandler Called when RSSI goes above maxRssi or below minRssi
     * @return 0 for success, -1 for failure
     */
    public int startRssiMonitoring(byte maxRssi, byte minRssi,
                                   WifiRssiEventHandler rssiEventHandler) {
        return mWifiVendorHal.startRssiMonitoring(maxRssi, minRssi, rssiEventHandler);
    }

    public int stopRssiMonitoring() {
        return mWifiVendorHal.stopRssiMonitoring();
    }

    /**
     * Fetch the host wakeup reasons stats from wlan driver.
     *
     * @return the |WifiWakeReasonAndCounts| object retrieved from the wlan driver.
     */
    public WifiWakeReasonAndCounts getWlanWakeReasonCount() {
        return mWifiVendorHal.getWlanWakeReasonCount();
    }

    /**
     * Enable/Disable Neighbour discovery offload functionality in the firmware.
     *
     * @param enabled true to enable, false to disable.
     * @return true for success, false otherwise.
     */
    public boolean configureNeighborDiscoveryOffload(boolean enabled) {
        return mWifiVendorHal.configureNeighborDiscoveryOffload(enabled);
    }

    // Firmware roaming control.

    /**
     * Class to retrieve firmware roaming capability parameters.
     */
    public static class RoamingCapabilities {
        public int  maxBlacklistSize;
        public int  maxWhitelistSize;
    }

    /**
     * Query the firmware roaming capabilities.
     * @return true for success, false otherwise.
     */
    public boolean getRoamingCapabilities(RoamingCapabilities capabilities) {
        return mWifiVendorHal.getRoamingCapabilities(capabilities);
    }

    /**
     * Macros for controlling firmware roaming.
     */
    public static final int DISABLE_FIRMWARE_ROAMING = 0;
    public static final int ENABLE_FIRMWARE_ROAMING = 1;

    /**
     * Enable/disable firmware roaming.
     *
     * @return error code returned from HAL.
     */
    public int enableFirmwareRoaming(int state) {
        return mWifiVendorHal.enableFirmwareRoaming(state);
    }

    /**
     * Class for specifying the roaming configurations.
     */
    public static class RoamingConfig {
        public ArrayList<String> blacklistBssids;
        public ArrayList<String> whitelistSsids;
    }

    /**
     * Set firmware roaming configurations.
     */
    public boolean configureRoaming(RoamingConfig config) {
        Log.d(mTAG, "configureRoaming ");
        return mWifiVendorHal.configureRoaming(config);
    }

    /**
     * Reset firmware roaming configuration.
     */
    public boolean resetRoamingConfiguration() {
        // Pass in an empty RoamingConfig object which translates to zero size
        // blacklist and whitelist to reset the firmware roaming configuration.
        return mWifiVendorHal.configureRoaming(new RoamingConfig());
    }

    /**
     * Tx power level scenarios that can be selected.
     */
    public static final int TX_POWER_SCENARIO_NORMAL = 0;
    public static final int TX_POWER_SCENARIO_VOICE_CALL = 1;

    /**
     * Select one of the pre-configured TX power level scenarios or reset it back to normal.
     * Primarily used for meeting SAR requirements during voice calls.
     *
     * @param scenario Should be one {@link #TX_POWER_SCENARIO_NORMAL} or
     *        {@link #TX_POWER_SCENARIO_VOICE_CALL}.
     * @return true for success; false for failure or if the HAL version does not support this API.
     */
    public boolean selectTxPowerScenario(int scenario) {
        return mWifiVendorHal.selectTxPowerScenario(scenario);
    }

    /********************************************************
     * JNI operations
     ********************************************************/
    /* Register native functions */
    static {
        /* Native functions are defined in libwifi-service.so */
        System.loadLibrary("wifi-service");
        registerNatives();
    }

    private static native int registerNatives();
    /* kernel logging support */
    private static native byte[] readKernelLogNative();

    /**
     * Fetches the latest kernel logs.
     */
    public synchronized String readKernelLog() {
        byte[] bytes = readKernelLogNative();
        if (bytes != null) {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            try {
                CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
                return decoded.toString();
            } catch (CharacterCodingException cce) {
                return new String(bytes, StandardCharsets.ISO_8859_1);
            }
        } else {
            return "*** failed to read kernel log ***";
        }
    }
}
