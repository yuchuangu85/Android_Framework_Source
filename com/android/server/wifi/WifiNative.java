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

import android.net.wifi.BatchedScanSettings;
import android.net.wifi.RttManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiLinkLayerStats;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiScanner;
import android.net.wifi.RttManager;
import android.net.wifi.WifiSsid;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.net.wifi.WifiEnterpriseConfig;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Base64;
import android.util.LocalLog;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import libcore.util.HexEncoding;
/**
 * Native calls for bring up/shut down of the supplicant daemon and for
 * sending requests to the supplicant daemon
 *
 * waitForEvent() is called on the monitor thread for events. All other methods
 * must be serialized from the framework.
 *
 * {@hide}
 */
public class WifiNative {

    private static boolean DBG = false;
    private final String mTAG;
    private static final int DEFAULT_GROUP_OWNER_INTENT     = 6;

    static final int BLUETOOTH_COEXISTENCE_MODE_ENABLED     = 0;
    static final int BLUETOOTH_COEXISTENCE_MODE_DISABLED    = 1;
    static final int BLUETOOTH_COEXISTENCE_MODE_SENSE       = 2;

    static final int SCAN_WITHOUT_CONNECTION_SETUP          = 1;
    static final int SCAN_WITH_CONNECTION_SETUP             = 2;

    // Hold this lock before calling supplicant - it is required to
    // mutually exclude access from Wifi and P2p state machines
    static final Object mLock = new Object();

    public final String mInterfaceName;
    public final String mInterfacePrefix;

    private boolean mSuspendOptEnabled = false;

    private static final int EID_HT_OPERATION = 61;
    private static final int EID_VHT_OPERATION = 192;
    private static final int EID_EXTENDED_CAPS = 127;
    private static final int RTT_RESP_ENABLE_BIT = 70;
    /* Register native functions */

    static {
        /* Native functions are defined in libwifi-service.so */
        System.loadLibrary("wifi-service");
        registerNatives();
    }

    private static native int registerNatives();

    public native static boolean loadDriver();

    public native static boolean isDriverLoaded();

    public native static boolean unloadDriver();

    public native static boolean startSupplicant(boolean p2pSupported);

    /* Sends a kill signal to supplicant. To be used when we have lost connection
       or when the supplicant is hung */
    public native static boolean killSupplicant(boolean p2pSupported);

    private native boolean connectToSupplicantNative();

    private native void closeSupplicantConnectionNative();

    /**
     * Wait for the supplicant to send an event, returning the event string.
     * @return the event string sent by the supplicant.
     */
    private native String waitForEventNative();

    private native boolean doBooleanCommandNative(String command);

    private native int doIntCommandNative(String command);

    private native String doStringCommandNative(String command);

    public WifiNative(String interfaceName) {
        mInterfaceName = interfaceName;
        mTAG = "WifiNative-" + interfaceName;
        if (!interfaceName.equals("p2p0")) {
            mInterfacePrefix = "IFNAME=" + interfaceName + " ";
        } else {
            // commands for p2p0 interface don't need prefix
            mInterfacePrefix = "";
        }
    }

    void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            DBG = true;
        } else {
            DBG = false;
        }
    }

    private static final LocalLog mLocalLog = new LocalLog(16384);

    // hold mLock before accessing mCmdIdLock
    private static int sCmdId;

    public static LocalLog getLocalLog() {
        return mLocalLog;
    }

    private static int getNewCmdIdLocked() {
        return sCmdId++;
    }

    private void localLog(String s) {
        if (mLocalLog != null)
            mLocalLog.log(mInterfaceName + ": " + s);
    }

    public boolean connectToSupplicant() {
        synchronized(mLock) {
            localLog(mInterfacePrefix + "connectToSupplicant");
            return connectToSupplicantNative();
        }
    }

    public void closeSupplicantConnection() {
        synchronized(mLock) {
            localLog(mInterfacePrefix + "closeSupplicantConnection");
            closeSupplicantConnectionNative();
        }
    }

    public String waitForEvent() {
        // No synchronization necessary .. it is implemented in WifiMonitor
        return waitForEventNative();
    }

    private boolean doBooleanCommand(String command) {
        if (DBG) Log.d(mTAG, "doBoolean: " + command);
        synchronized (mLock) {
            int cmdId = getNewCmdIdLocked();
            String toLog = Integer.toString(cmdId) + ":" + mInterfacePrefix + command;
            boolean result = doBooleanCommandNative(mInterfacePrefix + command);
            localLog(toLog + " -> " + result);
            if (DBG) Log.d(mTAG, command + ": returned " + result);
            return result;
        }
    }

    private boolean doBooleanCommandWithoutLogging(String command) {
        if (DBG) Log.d(mTAG, "doBooleanCommandWithoutLogging: " + command);
        synchronized (mLock) {
            int cmdId = getNewCmdIdLocked();
            boolean result = doBooleanCommandNative(mInterfacePrefix + command);
            if (DBG) Log.d(mTAG, command + ": returned " + result);
            return result;
        }
    }

    private int doIntCommand(String command) {
        if (DBG) Log.d(mTAG, "doInt: " + command);
        synchronized (mLock) {
            int cmdId = getNewCmdIdLocked();
            String toLog = Integer.toString(cmdId) + ":" + mInterfacePrefix + command;
            int result = doIntCommandNative(mInterfacePrefix + command);
            localLog(toLog + " -> " + result);
            if (DBG) Log.d(mTAG, "   returned " + result);
            return result;
        }
    }

    private String doStringCommand(String command) {
        if (DBG) {
            //GET_NETWORK commands flood the logs
            if (!command.startsWith("GET_NETWORK")) {
                Log.d(mTAG, "doString: [" + command + "]");
            }
        }
        synchronized (mLock) {
            int cmdId = getNewCmdIdLocked();
            String toLog = Integer.toString(cmdId) + ":" + mInterfacePrefix + command;
            String result = doStringCommandNative(mInterfacePrefix + command);
            if (result == null) {
                if (DBG) Log.d(mTAG, "doStringCommandNative no result");
            } else {
                if (!command.startsWith("STATUS-")) {
                    localLog(toLog + " -> " + result);
                }
                if (DBG) Log.d(mTAG, "   returned " + result.replace("\n", " "));
            }
            return result;
        }
    }

    private String doStringCommandWithoutLogging(String command) {
        if (DBG) {
            //GET_NETWORK commands flood the logs
            if (!command.startsWith("GET_NETWORK")) {
                Log.d(mTAG, "doString: [" + command + "]");
            }
        }
        synchronized (mLock) {
            return doStringCommandNative(mInterfacePrefix + command);
        }
    }

    public boolean ping() {
        String pong = doStringCommand("PING");
        return (pong != null && pong.equals("PONG"));
    }

    public void setSupplicantLogLevel(String level) {
        doStringCommand("LOG_LEVEL " + level);
    }

    public String getFreqCapability() {
        return doStringCommand("GET_CAPABILITY freq");
    }

    public boolean scan(int type, String freqList) {
        if (type == SCAN_WITHOUT_CONNECTION_SETUP) {
            if (freqList == null) return doBooleanCommand("SCAN TYPE=ONLY");
            else return doBooleanCommand("SCAN TYPE=ONLY freq=" + freqList);
        } else if (type == SCAN_WITH_CONNECTION_SETUP) {
            if (freqList == null) return doBooleanCommand("SCAN");
            else return doBooleanCommand("SCAN freq=" + freqList);
        } else {
            throw new IllegalArgumentException("Invalid scan type");
        }
    }

    /* Does a graceful shutdown of supplicant. Is a common stop function for both p2p and sta.
     *
     * Note that underneath we use a harsh-sounding "terminate" supplicant command
     * for a graceful stop and a mild-sounding "stop" interface
     * to kill the process
     */
    public boolean stopSupplicant() {
        return doBooleanCommand("TERMINATE");
    }

    public String listNetworks() {
        return doStringCommand("LIST_NETWORKS");
    }

    public String listNetworks(int last_id) {
        return doStringCommand("LIST_NETWORKS LAST_ID=" + last_id);
    }

    public int addNetwork() {
        return doIntCommand("ADD_NETWORK");
    }

    public boolean setNetworkVariable(int netId, String name, String value) {
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(value)) return false;
        if (name.equals(WifiConfiguration.pskVarName)
                || name.equals(WifiEnterpriseConfig.PASSWORD_KEY)) {
            return doBooleanCommandWithoutLogging("SET_NETWORK " + netId + " " + name + " " + value);
        } else {
            return doBooleanCommand("SET_NETWORK " + netId + " " + name + " " + value);
        }
    }

    public String getNetworkVariable(int netId, String name) {
        if (TextUtils.isEmpty(name)) return null;

        // GET_NETWORK will likely flood the logs ...
        return doStringCommandWithoutLogging("GET_NETWORK " + netId + " " + name);
    }

    public boolean removeNetwork(int netId) {
        return doBooleanCommand("REMOVE_NETWORK " + netId);
    }


    private void logDbg(String debug) {
        long now = SystemClock.elapsedRealtimeNanos();
        String ts = String.format("[%,d us] ", now/1000);
        Log.e("WifiNative: ", ts+debug+ " stack:"
                + Thread.currentThread().getStackTrace()[2].getMethodName() +" - "
                + Thread.currentThread().getStackTrace()[3].getMethodName() +" - "
                + Thread.currentThread().getStackTrace()[4].getMethodName() +" - "
                + Thread.currentThread().getStackTrace()[5].getMethodName()+" - "
                + Thread.currentThread().getStackTrace()[6].getMethodName());

    }
    public boolean enableNetwork(int netId, boolean disableOthers) {
        if (DBG) logDbg("enableNetwork nid=" + Integer.toString(netId)
                + " disableOthers=" + disableOthers);
        if (disableOthers) {
            return doBooleanCommand("SELECT_NETWORK " + netId);
        } else {
            return doBooleanCommand("ENABLE_NETWORK " + netId);
        }
    }

    public boolean disableNetwork(int netId) {
        if (DBG) logDbg("disableNetwork nid=" + Integer.toString(netId));
        return doBooleanCommand("DISABLE_NETWORK " + netId);
    }

    public boolean selectNetwork(int netId) {
        if (DBG) logDbg("selectNetwork nid=" + Integer.toString(netId));
        return doBooleanCommand("SELECT_NETWORK " + netId);
    }

    public boolean reconnect() {
        if (DBG) logDbg("RECONNECT ");
        return doBooleanCommand("RECONNECT");
    }

    public boolean reassociate() {
        if (DBG) logDbg("REASSOCIATE ");
        return doBooleanCommand("REASSOCIATE");
    }

    public boolean disconnect() {
        if (DBG) logDbg("DISCONNECT ");
        return doBooleanCommand("DISCONNECT");
    }

    public String status() {
        return status(false);
    }

    public String status(boolean noEvents) {
        if (noEvents) {
            return doStringCommand("STATUS-NO_EVENTS");
        } else {
            return doStringCommand("STATUS");
        }
    }

    public String getMacAddress() {
        //Macaddr = XX.XX.XX.XX.XX.XX
        String ret = doStringCommand("DRIVER MACADDR");
        if (!TextUtils.isEmpty(ret)) {
            String[] tokens = ret.split(" = ");
            if (tokens.length == 2) return tokens[1];
        }
        return null;
    }



    /**
     * Format of results:
     * =================
     * id=1
     * bssid=68:7f:74:d7:1b:6e
     * freq=2412
     * level=-43
     * tsf=1344621975160944
     * age=2623
     * flags=[WPA2-PSK-CCMP][WPS][ESS]
     * ssid=zubyb
     * ====
     *
     * RANGE=ALL gets all scan results
     * RANGE=ID- gets results from ID
     * MASK=<N> see wpa_supplicant/src/common/wpa_ctrl.h for details
     * 0                         0                        1                       0     2
     *                           WPA_BSS_MASK_MESH_SCAN | WPA_BSS_MASK_DELIM    | WPA_BSS_MASK_WIFI_DISPLAY
     * 0                         0                        0                       1     1   -> 9
     * WPA_BSS_MASK_INTERNETW  | WPA_BSS_MASK_P2P_SCAN  | WPA_BSS_MASK_WPS_SCAN | WPA_BSS_MASK_SSID
     * 1                         0                        0                       1     9   -> d
     * WPA_BSS_MASK_FLAGS      | WPA_BSS_MASK_IE        | WPA_BSS_MASK_AGE      | WPA_BSS_MASK_TSF
     * 1                         0                        0                       0     8
     * WPA_BSS_MASK_LEVEL      | WPA_BSS_MASK_NOISE     | WPA_BSS_MASK_QUAL     | WPA_BSS_MASK_CAPABILITIES
     * 0                         1                        1                       1     7
     * WPA_BSS_MASK_BEACON_INT | WPA_BSS_MASK_FREQ      | WPA_BSS_MASK_BSSID    | WPA_BSS_MASK_ID
     *
     * WPA_BSS_MASK_INTERNETW adds ANQP info (ctrl_iface:4151-4176)
     *
     * ctrl_iface.c:wpa_supplicant_ctrl_iface_process:7884
     *  wpa_supplicant_ctrl_iface_bss:4315
     *  print_bss_info
     */
    public String scanResults(int sid) {
        return doStringCommandWithoutLogging("BSS RANGE=" + sid + "- MASK=0x29d87");
    }

    public String doCustomCommand(String command) {
        return doStringCommand(command);
    }

    /**
     * Format of result:
     * id=1016
     * bssid=00:03:7f:40:84:10
     * freq=2462
     * beacon_int=200
     * capabilities=0x0431
     * qual=0
     * noise=0
     * level=-46
     * tsf=0000002669008476
     * age=5
     * ie=00105143412d485332302d52322d54455354010882848b960c12182403010b0706555...
     * flags=[WPA2-EAP-CCMP][ESS][P2P][HS20]
     * ssid=QCA-HS20-R2-TEST
     * p2p_device_name=
     * p2p_config_methods=0x0SET_NE
     * anqp_venue_name=02083d656e6757692d466920416c6c69616e63650a3239383920436f...
     * anqp_network_auth_type=010000
     * anqp_roaming_consortium=03506f9a05001bc504bd
     * anqp_ip_addr_type_availability=0c
     * anqp_nai_realm=0200300000246d61696c2e6578616d706c652e636f6d3b636973636f2...
     * anqp_3gpp=000600040132f465
     * anqp_domain_name=0b65786d61706c652e636f6d
     * hs20_operator_friendly_name=11656e6757692d466920416c6c69616e63650e636869...
     * hs20_wan_metrics=01c40900008001000000000a00
     * hs20_connection_capability=0100000006140001061600000650000106bb010106bb0...
     * hs20_osu_providers_list=0b5143412d4f53552d425353010901310015656e6757692d...
     */
    public String scanResult(String bssid) {
        return doStringCommand("BSS " + bssid);
    }

    /**
     * Format of command
     * DRIVER WLS_BATCHING SET SCANFREQ=x MSCAN=r BESTN=y CHANNEL=<z, w, t> RTT=s
     * where x is an ascii representation of an integer number of seconds between scans
     *       r is an ascii representation of an integer number of scans per batch
     *       y is an ascii representation of an integer number of the max AP to remember per scan
     *       z, w, t represent a 1..n size list of channel numbers and/or 'A', 'B' values
     *           indicating entire ranges of channels
     *       s is an ascii representation of an integer number of highest-strength AP
     *           for which we'd like approximate distance reported
     *
     * The return value is an ascii integer representing a guess of the number of scans
     * the firmware can remember before it runs out of buffer space or -1 on error
     */
    public String setBatchedScanSettings(BatchedScanSettings settings) {
        if (settings == null) {
            return doStringCommand("DRIVER WLS_BATCHING STOP");
        }
        String cmd = "DRIVER WLS_BATCHING SET SCANFREQ=" + settings.scanIntervalSec;
        cmd += " MSCAN=" + settings.maxScansPerBatch;
        if (settings.maxApPerScan != BatchedScanSettings.UNSPECIFIED) {
            cmd += " BESTN=" + settings.maxApPerScan;
        }
        if (settings.channelSet != null && !settings.channelSet.isEmpty()) {
            cmd += " CHANNEL=<";
            int i = 0;
            for (String channel : settings.channelSet) {
                cmd += (i > 0 ? "," : "") + channel;
                ++i;
            }
            cmd += ">";
        }
        if (settings.maxApForDistance != BatchedScanSettings.UNSPECIFIED) {
            cmd += " RTT=" + settings.maxApForDistance;
        }
        return doStringCommand(cmd);
    }

    public String getBatchedScanResults() {
        return doStringCommand("DRIVER WLS_BATCHING GET");
    }

    public boolean startDriver() {
        return doBooleanCommand("DRIVER START");
    }

    public boolean stopDriver() {
        return doBooleanCommand("DRIVER STOP");
    }


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
        return doBooleanCommand("DRIVER RXFILTER-STOP")
            && doBooleanCommand("DRIVER RXFILTER-REMOVE 2")
            && doBooleanCommand("DRIVER RXFILTER-START");
    }

    /**
     * Stop filtering out Multicast V4 packets.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean stopFilteringMulticastV4Packets() {
        return doBooleanCommand("DRIVER RXFILTER-STOP")
            && doBooleanCommand("DRIVER RXFILTER-ADD 2")
            && doBooleanCommand("DRIVER RXFILTER-START");
    }

    /**
     * Start filtering out Multicast V6 packets
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean startFilteringMulticastV6Packets() {
        return doBooleanCommand("DRIVER RXFILTER-STOP")
            && doBooleanCommand("DRIVER RXFILTER-REMOVE 3")
            && doBooleanCommand("DRIVER RXFILTER-START");
    }

    /**
     * Stop filtering out Multicast V6 packets.
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean stopFilteringMulticastV6Packets() {
        return doBooleanCommand("DRIVER RXFILTER-STOP")
            && doBooleanCommand("DRIVER RXFILTER-ADD 3")
            && doBooleanCommand("DRIVER RXFILTER-START");
    }

    /**
     * Set the operational frequency band
     * @param band One of
     *     {@link WifiManager#WIFI_FREQUENCY_BAND_AUTO},
     *     {@link WifiManager#WIFI_FREQUENCY_BAND_5GHZ},
     *     {@link WifiManager#WIFI_FREQUENCY_BAND_2GHZ},
     * @return {@code true} if the operation succeeded, {@code false} otherwise
     */
    public boolean setBand(int band) {
        String bandstr;

        if (band == WifiManager.WIFI_FREQUENCY_BAND_5GHZ)
            bandstr = "5G";
        else if (band == WifiManager.WIFI_FREQUENCY_BAND_2GHZ)
            bandstr = "2G";
        else
            bandstr = "AUTO";
        return doBooleanCommand("SET SETBAND " + bandstr);
    }

    /**
      * Sets the bluetooth coexistence mode.
      *
      * @param mode One of {@link #BLUETOOTH_COEXISTENCE_MODE_DISABLED},
      *            {@link #BLUETOOTH_COEXISTENCE_MODE_ENABLED}, or
      *            {@link #BLUETOOTH_COEXISTENCE_MODE_SENSE}.
      * @return Whether the mode was successfully set.
      */
    public boolean setBluetoothCoexistenceMode(int mode) {
        return doBooleanCommand("DRIVER BTCOEXMODE " + mode);
    }

    /**
     * Enable or disable Bluetooth coexistence scan mode. When this mode is on,
     * some of the low-level scan parameters used by the driver are changed to
     * reduce interference with A2DP streaming.
     *
     * @param isSet whether to enable or disable this mode
     * @return {@code true} if the command succeeded, {@code false} otherwise.
     */
    public boolean setBluetoothCoexistenceScanMode(boolean setCoexScanMode) {
        if (setCoexScanMode) {
            return doBooleanCommand("DRIVER BTCOEXSCAN-START");
        } else {
            return doBooleanCommand("DRIVER BTCOEXSCAN-STOP");
        }
    }

    public void enableSaveConfig() {
        doBooleanCommand("SET update_config 1");
    }

    public boolean saveConfig() {
        return doBooleanCommand("SAVE_CONFIG");
    }

    public boolean addToBlacklist(String bssid) {
        if (TextUtils.isEmpty(bssid)) return false;
        return doBooleanCommand("BLACKLIST " + bssid);
    }

    public boolean clearBlacklist() {
        return doBooleanCommand("BLACKLIST clear");
    }

    public boolean setSuspendOptimizations(boolean enabled) {
       // if (mSuspendOptEnabled == enabled) return true;
        mSuspendOptEnabled = enabled;

        Log.e("native", "do suspend " + enabled);
        if (enabled) {
            return doBooleanCommand("DRIVER SETSUSPENDMODE 1");
        } else {
            return doBooleanCommand("DRIVER SETSUSPENDMODE 0");
        }
    }

    public boolean setCountryCode(String countryCode) {
        if (countryCode != null)
            return doBooleanCommand("DRIVER COUNTRY " + countryCode.toUpperCase(Locale.ROOT));
        else
            return doBooleanCommand("DRIVER COUNTRY");
    }

    public boolean enableBackgroundScan(boolean enable) {
        boolean ret;
        if (enable) {
            ret = doBooleanCommand("SET pno 1");
        } else {
            ret = doBooleanCommand("SET pno 0");
        }
        return ret;
    }

    public void enableAutoConnect(boolean enable) {
        if (enable) {
            doBooleanCommand("STA_AUTOCONNECT 1");
        } else {
            doBooleanCommand("STA_AUTOCONNECT 0");
        }
    }

    public void setScanInterval(int scanInterval) {
        doBooleanCommand("SCAN_INTERVAL " + scanInterval);
    }

    public void startTdls(String macAddr, boolean enable) {
        if (enable) {
            doBooleanCommand("TDLS_DISCOVER " + macAddr);
            doBooleanCommand("TDLS_SETUP " + macAddr);
        } else {
            doBooleanCommand("TDLS_TEARDOWN " + macAddr);
        }
    }

    /** Example output:
     * RSSI=-65
     * LINKSPEED=48
     * NOISE=9999
     * FREQUENCY=0
     */
    public String signalPoll() {
        return doStringCommandWithoutLogging("SIGNAL_POLL");
    }

    /** Example outout:
     * TXGOOD=396
     * TXBAD=1
     */
    public String pktcntPoll() {
        return doStringCommand("PKTCNT_POLL");
    }

    public void bssFlush() {
        doBooleanCommand("BSS_FLUSH 0");
    }

    public boolean startWpsPbc(String bssid) {
        if (TextUtils.isEmpty(bssid)) {
            return doBooleanCommand("WPS_PBC");
        } else {
            return doBooleanCommand("WPS_PBC " + bssid);
        }
    }

    public boolean startWpsPbc(String iface, String bssid) {
        synchronized (mLock) {
            if (TextUtils.isEmpty(bssid)) {
                return doBooleanCommandNative("IFNAME=" + iface + " WPS_PBC");
            } else {
                return doBooleanCommandNative("IFNAME=" + iface + " WPS_PBC " + bssid);
            }
        }
    }

    public boolean startWpsPinKeypad(String pin) {
        if (TextUtils.isEmpty(pin)) return false;
        return doBooleanCommand("WPS_PIN any " + pin);
    }

    public boolean startWpsPinKeypad(String iface, String pin) {
        if (TextUtils.isEmpty(pin)) return false;
        synchronized (mLock) {
            return doBooleanCommandNative("IFNAME=" + iface + " WPS_PIN any " + pin);
        }
    }


    public String startWpsPinDisplay(String bssid) {
        if (TextUtils.isEmpty(bssid)) {
            return doStringCommand("WPS_PIN any");
        } else {
            return doStringCommand("WPS_PIN " + bssid);
        }
    }

    public String startWpsPinDisplay(String iface, String bssid) {
        synchronized (mLock) {
            if (TextUtils.isEmpty(bssid)) {
                return doStringCommandNative("IFNAME=" + iface + " WPS_PIN any");
            } else {
                return doStringCommandNative("IFNAME=" + iface + " WPS_PIN " + bssid);
            }
        }
    }

    public boolean setExternalSim(boolean external) {
        synchronized (mLock) {
            String value = external ? "1" : "0";
            Log.d(TAG, "Setting external_sim to " + value);
            return doBooleanCommand("SET external_sim " + value);
        }
    }

    public boolean simAuthResponse(int id, String type, String response) {
        // with type = GSM-AUTH, UMTS-AUTH or UMTS-AUTS
        synchronized (mLock) {
            return doBooleanCommand("CTRL-RSP-SIM-" + id + ":" + type + response);
        }
    }

    public boolean simIdentityResponse(int id, String response) {
        synchronized (mLock) {
            return doBooleanCommand("CTRL-RSP-IDENTITY-" + id + ":" + response);
        }
    }

    /* Configures an access point connection */
    public boolean startWpsRegistrar(String bssid, String pin) {
        if (TextUtils.isEmpty(bssid) || TextUtils.isEmpty(pin)) return false;
        return doBooleanCommand("WPS_REG " + bssid + " " + pin);
    }

    public boolean cancelWps() {
        return doBooleanCommand("WPS_CANCEL");
    }

    public boolean setPersistentReconnect(boolean enabled) {
        int value = (enabled == true) ? 1 : 0;
        return doBooleanCommand("SET persistent_reconnect " + value);
    }

    public boolean setDeviceName(String name) {
        return doBooleanCommand("SET device_name " + name);
    }

    public boolean setDeviceType(String type) {
        return doBooleanCommand("SET device_type " + type);
    }

    public boolean setConfigMethods(String cfg) {
        return doBooleanCommand("SET config_methods " + cfg);
    }

    public boolean setManufacturer(String value) {
        return doBooleanCommand("SET manufacturer " + value);
    }

    public boolean setModelName(String value) {
        return doBooleanCommand("SET model_name " + value);
    }

    public boolean setModelNumber(String value) {
        return doBooleanCommand("SET model_number " + value);
    }

    public boolean setSerialNumber(String value) {
        return doBooleanCommand("SET serial_number " + value);
    }

    public boolean setP2pSsidPostfix(String postfix) {
        return doBooleanCommand("SET p2p_ssid_postfix " + postfix);
    }

    public boolean setP2pGroupIdle(String iface, int time) {
        synchronized (mLock) {
            return doBooleanCommandNative("IFNAME=" + iface + " SET p2p_group_idle " + time);
        }
    }

    public void setPowerSave(boolean enabled) {
        if (enabled) {
            doBooleanCommand("SET ps 1");
        } else {
            doBooleanCommand("SET ps 0");
        }
    }

    public boolean setP2pPowerSave(String iface, boolean enabled) {
        synchronized (mLock) {
            if (enabled) {
                return doBooleanCommandNative("IFNAME=" + iface + " P2P_SET ps 1");
            } else {
                return doBooleanCommandNative("IFNAME=" + iface + " P2P_SET ps 0");
            }
        }
    }

    public boolean setWfdEnable(boolean enable) {
        return doBooleanCommand("SET wifi_display " + (enable ? "1" : "0"));
    }

    public boolean setWfdDeviceInfo(String hex) {
        return doBooleanCommand("WFD_SUBELEM_SET 0 " + hex);
    }

    /**
     * "sta" prioritizes STA connection over P2P and "p2p" prioritizes
     * P2P connection over STA
     */
    public boolean setConcurrencyPriority(String s) {
        return doBooleanCommand("P2P_SET conc_pref " + s);
    }

    public boolean p2pFind() {
        return doBooleanCommand("P2P_FIND");
    }

    public boolean p2pFind(int timeout) {
        if (timeout <= 0) {
            return p2pFind();
        }
        return doBooleanCommand("P2P_FIND " + timeout);
    }

    public boolean p2pStopFind() {
       return doBooleanCommand("P2P_STOP_FIND");
    }

    public boolean p2pListen() {
        return doBooleanCommand("P2P_LISTEN");
    }

    public boolean p2pListen(int timeout) {
        if (timeout <= 0) {
            return p2pListen();
        }
        return doBooleanCommand("P2P_LISTEN " + timeout);
    }

    public boolean p2pExtListen(boolean enable, int period, int interval) {
        if (enable && interval < period) {
            return false;
        }
        return doBooleanCommand("P2P_EXT_LISTEN"
                    + (enable ? (" " + period + " " + interval) : ""));
    }

    public boolean p2pSetChannel(int lc, int oc) {
        if (DBG) Log.d(mTAG, "p2pSetChannel: lc="+lc+", oc="+oc);

        if (lc >=1 && lc <= 11) {
            if (!doBooleanCommand("P2P_SET listen_channel " + lc)) {
                return false;
            }
        } else if (lc != 0) {
            return false;
        }

        if (oc >= 1 && oc <= 165 ) {
            int freq = (oc <= 14 ? 2407 : 5000) + oc * 5;
            return doBooleanCommand("P2P_SET disallow_freq 1000-"
                    + (freq - 5) + "," + (freq + 5) + "-6000");
        } else if (oc == 0) {
            /* oc==0 disables "P2P_SET disallow_freq" (enables all freqs) */
            return doBooleanCommand("P2P_SET disallow_freq \"\"");
        }

        return false;
    }

    public boolean p2pFlush() {
        return doBooleanCommand("P2P_FLUSH");
    }

    /* p2p_connect <peer device address> <pbc|pin|PIN#> [label|display|keypad]
        [persistent] [join|auth] [go_intent=<0..15>] [freq=<in MHz>] */
    public String p2pConnect(WifiP2pConfig config, boolean joinExistingGroup) {
        if (config == null) return null;
        List<String> args = new ArrayList<String>();
        WpsInfo wps = config.wps;
        args.add(config.deviceAddress);

        switch (wps.setup) {
            case WpsInfo.PBC:
                args.add("pbc");
                break;
            case WpsInfo.DISPLAY:
                if (TextUtils.isEmpty(wps.pin)) {
                    args.add("pin");
                } else {
                    args.add(wps.pin);
                }
                args.add("display");
                break;
            case WpsInfo.KEYPAD:
                args.add(wps.pin);
                args.add("keypad");
                break;
            case WpsInfo.LABEL:
                args.add(wps.pin);
                args.add("label");
            default:
                break;
        }

        if (config.netId == WifiP2pGroup.PERSISTENT_NET_ID) {
            args.add("persistent");
        }

        if (joinExistingGroup) {
            args.add("join");
        } else {
            //TODO: This can be adapted based on device plugged in state and
            //device battery state
            int groupOwnerIntent = config.groupOwnerIntent;
            if (groupOwnerIntent < 0 || groupOwnerIntent > 15) {
                groupOwnerIntent = DEFAULT_GROUP_OWNER_INTENT;
            }
            args.add("go_intent=" + groupOwnerIntent);
        }

        String command = "P2P_CONNECT ";
        for (String s : args) command += s + " ";

        return doStringCommand(command);
    }

    public boolean p2pCancelConnect() {
        return doBooleanCommand("P2P_CANCEL");
    }

    public boolean p2pProvisionDiscovery(WifiP2pConfig config) {
        if (config == null) return false;

        switch (config.wps.setup) {
            case WpsInfo.PBC:
                return doBooleanCommand("P2P_PROV_DISC " + config.deviceAddress + " pbc");
            case WpsInfo.DISPLAY:
                //We are doing display, so provision discovery is keypad
                return doBooleanCommand("P2P_PROV_DISC " + config.deviceAddress + " keypad");
            case WpsInfo.KEYPAD:
                //We are doing keypad, so provision discovery is display
                return doBooleanCommand("P2P_PROV_DISC " + config.deviceAddress + " display");
            default:
                break;
        }
        return false;
    }

    public boolean p2pGroupAdd(boolean persistent) {
        if (persistent) {
            return doBooleanCommand("P2P_GROUP_ADD persistent");
        }
        return doBooleanCommand("P2P_GROUP_ADD");
    }

    public boolean p2pGroupAdd(int netId) {
        return doBooleanCommand("P2P_GROUP_ADD persistent=" + netId);
    }

    public boolean p2pGroupRemove(String iface) {
        if (TextUtils.isEmpty(iface)) return false;
        synchronized (mLock) {
            return doBooleanCommandNative("IFNAME=" + iface + " P2P_GROUP_REMOVE " + iface);
        }
    }

    public boolean p2pReject(String deviceAddress) {
        return doBooleanCommand("P2P_REJECT " + deviceAddress);
    }

    /* Invite a peer to a group */
    public boolean p2pInvite(WifiP2pGroup group, String deviceAddress) {
        if (TextUtils.isEmpty(deviceAddress)) return false;

        if (group == null) {
            return doBooleanCommand("P2P_INVITE peer=" + deviceAddress);
        } else {
            return doBooleanCommand("P2P_INVITE group=" + group.getInterface()
                    + " peer=" + deviceAddress + " go_dev_addr=" + group.getOwner().deviceAddress);
        }
    }

    /* Reinvoke a persistent connection */
    public boolean p2pReinvoke(int netId, String deviceAddress) {
        if (TextUtils.isEmpty(deviceAddress) || netId < 0) return false;

        return doBooleanCommand("P2P_INVITE persistent=" + netId + " peer=" + deviceAddress);
    }

    public String p2pGetSsid(String deviceAddress) {
        return p2pGetParam(deviceAddress, "oper_ssid");
    }

    public String p2pGetDeviceAddress() {

        Log.d(TAG, "p2pGetDeviceAddress");

        String status = null;

        /* Explicitly calling the API without IFNAME= prefix to take care of the devices that
        don't have p2p0 interface. Supplicant seems to be returning the correct address anyway. */

        synchronized (mLock) {
            status = doStringCommandNative("STATUS");
        }

        String result = "";
        if (status != null) {
            String[] tokens = status.split("\n");
            for (String token : tokens) {
                if (token.startsWith("p2p_device_address=")) {
                    String[] nameValue = token.split("=");
                    if (nameValue.length != 2)
                        break;
                    result = nameValue[1];
                }
            }
        }

        Log.d(TAG, "p2pGetDeviceAddress returning " + result);
        return result;
    }

    public int getGroupCapability(String deviceAddress) {
        int gc = 0;
        if (TextUtils.isEmpty(deviceAddress)) return gc;
        String peerInfo = p2pPeer(deviceAddress);
        if (TextUtils.isEmpty(peerInfo)) return gc;

        String[] tokens = peerInfo.split("\n");
        for (String token : tokens) {
            if (token.startsWith("group_capab=")) {
                String[] nameValue = token.split("=");
                if (nameValue.length != 2) break;
                try {
                    return Integer.decode(nameValue[1]);
                } catch(NumberFormatException e) {
                    return gc;
                }
            }
        }
        return gc;
    }

    public String p2pPeer(String deviceAddress) {
        return doStringCommand("P2P_PEER " + deviceAddress);
    }

    private String p2pGetParam(String deviceAddress, String key) {
        if (deviceAddress == null) return null;

        String peerInfo = p2pPeer(deviceAddress);
        if (peerInfo == null) return null;
        String[] tokens= peerInfo.split("\n");

        key += "=";
        for (String token : tokens) {
            if (token.startsWith(key)) {
                String[] nameValue = token.split("=");
                if (nameValue.length != 2) break;
                return nameValue[1];
            }
        }
        return null;
    }

    public boolean p2pServiceAdd(WifiP2pServiceInfo servInfo) {
        /*
         * P2P_SERVICE_ADD bonjour <query hexdump> <RDATA hexdump>
         * P2P_SERVICE_ADD upnp <version hex> <service>
         *
         * e.g)
         * [Bonjour]
         * # IP Printing over TCP (PTR) (RDATA=MyPrinter._ipp._tcp.local.)
         * P2P_SERVICE_ADD bonjour 045f697070c00c000c01 094d795072696e746572c027
         * # IP Printing over TCP (TXT) (RDATA=txtvers=1,pdl=application/postscript)
         * P2P_SERVICE_ADD bonjour 096d797072696e746572045f697070c00c001001
         *  09747874766572733d311a70646c3d6170706c69636174696f6e2f706f7374736372797074
         *
         * [UPnP]
         * P2P_SERVICE_ADD upnp 10 uuid:6859dede-8574-59ab-9332-123456789012
         * P2P_SERVICE_ADD upnp 10 uuid:6859dede-8574-59ab-9332-123456789012::upnp:rootdevice
         * P2P_SERVICE_ADD upnp 10 uuid:6859dede-8574-59ab-9332-123456789012::urn:schemas-upnp
         * -org:device:InternetGatewayDevice:1
         * P2P_SERVICE_ADD upnp 10 uuid:6859dede-8574-59ab-9322-123456789012::urn:schemas-upnp
         * -org:service:ContentDirectory:2
         */
        for (String s : servInfo.getSupplicantQueryList()) {
            String command = "P2P_SERVICE_ADD";
            command += (" " + s);
            if (!doBooleanCommand(command)) {
                return false;
            }
        }
        return true;
    }

    public boolean p2pServiceDel(WifiP2pServiceInfo servInfo) {
        /*
         * P2P_SERVICE_DEL bonjour <query hexdump>
         * P2P_SERVICE_DEL upnp <version hex> <service>
         */
        for (String s : servInfo.getSupplicantQueryList()) {
            String command = "P2P_SERVICE_DEL ";

            String[] data = s.split(" ");
            if (data.length < 2) {
                return false;
            }
            if ("upnp".equals(data[0])) {
                command += s;
            } else if ("bonjour".equals(data[0])) {
                command += data[0];
                command += (" " + data[1]);
            } else {
                return false;
            }
            if (!doBooleanCommand(command)) {
                return false;
            }
        }
        return true;
    }

    public boolean p2pServiceFlush() {
        return doBooleanCommand("P2P_SERVICE_FLUSH");
    }

    public String p2pServDiscReq(String addr, String query) {
        String command = "P2P_SERV_DISC_REQ";
        command += (" " + addr);
        command += (" " + query);

        return doStringCommand(command);
    }

    public boolean p2pServDiscCancelReq(String id) {
        return doBooleanCommand("P2P_SERV_DISC_CANCEL_REQ " + id);
    }

    /* Set the current mode of miracast operation.
     *  0 = disabled
     *  1 = operating as source
     *  2 = operating as sink
     */
    public void setMiracastMode(int mode) {
        // Note: optional feature on the driver. It is ok for this to fail.
        doBooleanCommand("DRIVER MIRACAST " + mode);
    }

    public boolean fetchAnqp(String bssid, String subtypes) {
        return doBooleanCommand("ANQP_GET " + bssid + " " + subtypes);
    }

    /*
     * NFC-related calls
     */
    public String getNfcWpsConfigurationToken(int netId) {
        return doStringCommand("WPS_NFC_CONFIG_TOKEN WPS " + netId);
    }

    public String getNfcHandoverRequest() {
        return doStringCommand("NFC_GET_HANDOVER_REQ NDEF P2P-CR");
    }

    public String getNfcHandoverSelect() {
        return doStringCommand("NFC_GET_HANDOVER_SEL NDEF P2P-CR");
    }

    public boolean initiatorReportNfcHandover(String selectMessage) {
        return doBooleanCommand("NFC_REPORT_HANDOVER INIT P2P 00 " + selectMessage);
    }

    public boolean responderReportNfcHandover(String requestMessage) {
        return doBooleanCommand("NFC_REPORT_HANDOVER RESP P2P " + requestMessage + " 00");
    }

    /* WIFI HAL support */

    private static final String TAG = "WifiNative-HAL";
    private static long sWifiHalHandle = 0;             /* used by JNI to save wifi_handle */
    private static long[] sWifiIfaceHandles = null;     /* used by JNI to save interface handles */
    private static int sWlan0Index = -1;
    private static int sP2p0Index = -1;
    private static MonitorThread sThread;
    private static final int STOP_HAL_TIMEOUT_MS = 1000;

    private static native boolean startHalNative();
    private static native void stopHalNative();
    private static native void waitForHalEventNative();

    private static class MonitorThread extends Thread {
        public void run() {
            Log.i(TAG, "Waiting for HAL events mWifiHalHandle=" + Long.toString(sWifiHalHandle));
            waitForHalEventNative();
        }
    }

    synchronized public static boolean startHal() {

        String debugLog = "startHal stack: ";
        java.lang.StackTraceElement[] elements = Thread.currentThread().getStackTrace();
        for (int i = 2; i < elements.length && i <= 7; i++ ) {
            debugLog = debugLog + " - " + elements[i].getMethodName();
        }

        mLocalLog.log(debugLog);

        synchronized (mLock) {
            if (startHalNative() && (getInterfaces() != 0) && (sWlan0Index != -1)) {
                sThread = new MonitorThread();
                sThread.start();
                return true;
            } else {
                if (DBG) mLocalLog.log("Could not start hal");
                Log.e(TAG, "Could not start hal");
                return false;
            }
        }
    }

    synchronized public static void stopHal() {
        synchronized (mLock) {
            if (isHalStarted()) {
                stopHalNative();
                try {
                    sThread.join(STOP_HAL_TIMEOUT_MS);
                    Log.d(TAG, "HAL event thread stopped successfully");
                } catch (InterruptedException e) {
                    Log.e(TAG, "Could not stop HAL cleanly");
                }
                sThread = null;
                sWifiHalHandle = 0;
                sWifiIfaceHandles = null;
                sWlan0Index = -1;
                sP2p0Index = -1;
            }
        }
    }

    public static boolean isHalStarted() {
        return (sWifiHalHandle != 0);
    }
    private static native int getInterfacesNative();

    synchronized public static int getInterfaces() {
        synchronized (mLock) {
            if (isHalStarted()) {
                if (sWifiIfaceHandles == null) {
                    int num = getInterfacesNative();
                    int wifi_num = 0;
                    for (int i = 0; i < num; i++) {
                        String name = getInterfaceNameNative(i);
                        Log.i(TAG, "interface[" + i + "] = " + name);
                        if (name.equals("wlan0")) {
                            sWlan0Index = i;
                            wifi_num++;
                        } else if (name.equals("p2p0")) {
                            sP2p0Index = i;
                            wifi_num++;
                        }
                    }
                    return wifi_num;
                } else {
                    return sWifiIfaceHandles.length;
                }
            } else {
                return 0;
            }
        }
    }

    private static native String getInterfaceNameNative(int index);
    synchronized public static String getInterfaceName(int index) {
        return getInterfaceNameNative(index);
    }

    public static class ScanCapabilities {
        public int  max_scan_cache_size;                 // in number of scan results??
        public int  max_scan_buckets;
        public int  max_ap_cache_per_scan;
        public int  max_rssi_sample_size;
        public int  max_scan_reporting_threshold;        // in number of scan results??
        public int  max_hotlist_bssids;
        public int  max_significant_wifi_change_aps;
    }

    synchronized public static boolean getScanCapabilities(ScanCapabilities capabilities) {
        synchronized (mLock) {
            return isHalStarted() && getScanCapabilitiesNative(sWlan0Index, capabilities);
        }
    }

    private static native boolean getScanCapabilitiesNative(
            int iface, ScanCapabilities capabilities);

    private static native boolean startScanNative(int iface, int id, ScanSettings settings);
    private static native boolean stopScanNative(int iface, int id);
    private static native WifiScanner.ScanData[] getScanResultsNative(int iface, boolean flush);
    private static native WifiLinkLayerStats getWifiLinkLayerStatsNative(int iface);
    private static native void setWifiLinkLayerStatsNative(int iface, int enable);

    public static class ChannelSettings {
        int frequency;
        int dwell_time_ms;
        boolean passive;
    }

    public static class BucketSettings {
        int bucket;
        int band;
        int period_ms;
        int report_events;
        int num_channels;
        ChannelSettings channels[];
    }

    public static class ScanSettings {
        int base_period_ms;
        int max_ap_per_scan;
        int report_threshold_percent;
        int report_threshold_num_scans;
        int num_buckets;
        BucketSettings buckets[];
    }

    public static interface ScanEventHandler {
        void onScanResultsAvailable();
        void onFullScanResult(ScanResult fullScanResult);
        void onScanStatus();
        void onScanPaused(WifiScanner.ScanData[] data);
        void onScanRestarted();
    }

    synchronized static void onScanResultsAvailable(int id) {
        if (sScanEventHandler  != null) {
            sScanEventHandler.onScanResultsAvailable();
        }
    }

    /* scan status, keep these values in sync with gscan.h */
    private static int WIFI_SCAN_BUFFER_FULL = 0;
    private static int WIFI_SCAN_COMPLETE = 1;

    synchronized static void onScanStatus(int status) {
        if (status == WIFI_SCAN_BUFFER_FULL) {
            /* we have a separate event to take care of this */
        } else if (status == WIFI_SCAN_COMPLETE) {
            if (sScanEventHandler  != null) {
                sScanEventHandler.onScanStatus();
            }
        }
    }

    public static  WifiSsid createWifiSsid (byte[] rawSsid) {
        String ssidHexString = String.valueOf(HexEncoding.encode(rawSsid));

        if (ssidHexString == null) {
            return null;
        }

        WifiSsid wifiSsid = WifiSsid.createFromHex(ssidHexString);

        return wifiSsid;
    }

    public static String ssidConvert(byte[] rawSsid) {
        String ssid;

        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(rawSsid));
            ssid = decoded.toString();
        } catch (CharacterCodingException cce) {
            ssid = null;
        }

        if (ssid == null) {
            ssid = new String(rawSsid, StandardCharsets.ISO_8859_1);
        }

        return ssid;
    }

    public static boolean setSsid(byte[] rawSsid, ScanResult result) {
        if (rawSsid == null || rawSsid.length == 0 || result == null) {
            return false;
        }

        result.SSID = ssidConvert(rawSsid);
        result.wifiSsid = createWifiSsid(rawSsid);
        return true;
    }

    static void populateScanResult(ScanResult result, byte bytes[], String dbg) {
        int num = 0;
        if (bytes == null) return;
        if (dbg == null) dbg = "";
        for (int i = 0; i < bytes.length - 1; ) {
            int type  = bytes[i] & 0xFF;
            int len = bytes[i + 1] & 0xFF;
            if (i + len + 2 > bytes.length) {
                Log.w(TAG, dbg + "bad length " + len + " of IE " + type + " from " + result.BSSID);
                Log.w(TAG, dbg + "ignoring the rest of the IEs");
                break;
            }
            num++;
            if (DBG) Log.i(TAG, dbg + "bytes[" + i + "] = [" + type + ", " + len + "]" + ", " +
                    "next = " + (i + len + 2));
            i += len + 2;
        }

        int secondChanelOffset = 0;
        byte channelMode = 0;
        byte centerFreqIndex1 = 0;
        byte centerFreqIndex2 = 0;

        boolean is80211McRTTResponder = false;

        ScanResult.InformationElement elements[] = new ScanResult.InformationElement[num];
        for (int i = 0, index = 0; i < num; i++) {
            int type  = bytes[index] & 0xFF;
            int len = bytes[index + 1] & 0xFF;
            if (DBG) Log.i(TAG, dbg + "index = " + index + ", type = " + type + ", len = " + len);
            ScanResult.InformationElement elem = new ScanResult.InformationElement();
            elem.id = type;
            elem.bytes = new byte[len];
            for (int j = 0; j < len; j++) {
                elem.bytes[j] = bytes[index + j + 2];
            }
            elements[i] = elem;
            int inforStart = index + 2;
            index += (len + 2);

            if(type == EID_HT_OPERATION) {
                secondChanelOffset = bytes[inforStart + 1] & 0x3;
            } else if(type == EID_VHT_OPERATION) {
                channelMode = bytes[inforStart];
                centerFreqIndex1 = bytes[inforStart + 1];
                centerFreqIndex2 = bytes[inforStart + 2];
            } else if (type == EID_EXTENDED_CAPS) {
                int tempIndex = RTT_RESP_ENABLE_BIT / 8;
                byte offset = RTT_RESP_ENABLE_BIT % 8;

                if(len < tempIndex + 1) {
                    is80211McRTTResponder = false;
                } else {
                    if ((bytes[inforStart + tempIndex] & ((byte)0x1 << offset)) != 0) {
                        is80211McRTTResponder = true;
                    } else {
                        is80211McRTTResponder = false;
                    }
                }
            }
        }

        if (is80211McRTTResponder) {
            result.setFlag(ScanResult.FLAG_80211mc_RESPONDER);
        } else {
            result.clearFlag(ScanResult.FLAG_80211mc_RESPONDER);
        }

        //handle RTT related information
        if (channelMode != 0) {
            // 80 or 160 MHz
            result.channelWidth = channelMode + 1;

            //convert channel index to frequency in MHz, channel 36 is 5180MHz
            result.centerFreq0 = (centerFreqIndex1 - 36) * 5 + 5180;

            if(channelMode > 1) { //160MHz
                result.centerFreq1 = (centerFreqIndex2 - 36) * 5 + 5180;
            } else {
                result.centerFreq1 = 0;
            }
        } else {
            //20 or 40 MHz
            if (secondChanelOffset != 0) {//40MHz
                result.channelWidth = 1;
                if (secondChanelOffset == 1) {
                    result.centerFreq0 = result.frequency + 20;
                } else if (secondChanelOffset == 3) {
                    result.centerFreq0 = result.frequency - 20;
                } else {
                    result.centerFreq0 = 0;
                    Log.e(TAG, dbg + ": Error on secondChanelOffset");
                }
            } else {
                result.centerFreq0  = 0;
                result.centerFreq1  = 0;
            }
            result.centerFreq1  = 0;
        }
        if(DBG) {
            Log.d(TAG, dbg + "SSID: " + result.SSID + " ChannelWidth is: " + result.channelWidth +
                    " PrimaryFreq: " + result.frequency +" mCenterfreq0: " + result.centerFreq0 +
                    " mCenterfreq1: " + result.centerFreq1 + (is80211McRTTResponder ?
                    "Support RTT reponder: " : "Do not support RTT responder"));
        }

        result.informationElements = elements;
    }

    synchronized static void onFullScanResult(int id, ScanResult result, byte bytes[]) {
        if (DBG) Log.i(TAG, "Got a full scan results event, ssid = " + result.SSID + ", " +
                "num = " + bytes.length);

        if (sScanEventHandler == null) {
            return;
        }
        populateScanResult(result, bytes, " onFullScanResult ");

        sScanEventHandler.onFullScanResult(result);
    }

    private static int sScanCmdId = 0;
    private static ScanEventHandler sScanEventHandler;
    private static ScanSettings sScanSettings;

    synchronized public static boolean startScan(
            ScanSettings settings, ScanEventHandler eventHandler) {
        synchronized (mLock) {
            if (isHalStarted()) {

                if (sScanCmdId != 0) {
                    stopScan();
                } else if (sScanSettings != null || sScanEventHandler != null) {
                /* current scan is paused; no need to stop it */
                }

                sScanCmdId = getNewCmdIdLocked();

                sScanSettings = settings;
                sScanEventHandler = eventHandler;

                if (startScanNative(sWlan0Index, sScanCmdId, settings) == false) {
                    sScanEventHandler = null;
                    sScanSettings = null;
                    sScanCmdId = 0;
                    return false;
                }

                return true;
            } else {
                return false;
            }
        }
    }

    synchronized public static void stopScan() {
        synchronized (mLock) {
            if (isHalStarted()) {
                stopScanNative(sWlan0Index, sScanCmdId);
                sScanSettings = null;
                sScanEventHandler = null;
                sScanCmdId = 0;
            }
        }
    }

    synchronized public static void pauseScan() {
        synchronized (mLock) {
            if (isHalStarted()) {
                if (sScanCmdId != 0 && sScanSettings != null && sScanEventHandler != null) {
                    Log.d(TAG, "Pausing scan");
                    WifiScanner.ScanData scanData[] = getScanResultsNative(sWlan0Index, true);
                    stopScanNative(sWlan0Index, sScanCmdId);
                    sScanCmdId = 0;
                    sScanEventHandler.onScanPaused(scanData);
                }
            }
        }
    }

    synchronized public static void restartScan() {
        synchronized (mLock) {
            if (isHalStarted()) {
                if (sScanCmdId == 0 && sScanSettings != null && sScanEventHandler != null) {
                    Log.d(TAG, "Restarting scan");
                    ScanEventHandler handler = sScanEventHandler;
                    ScanSettings settings = sScanSettings;
                    if (startScan(sScanSettings, sScanEventHandler)) {
                        sScanEventHandler.onScanRestarted();
                    } else {
                    /* we are still paused; don't change state */
                        sScanEventHandler = handler;
                        sScanSettings = settings;
                    }
                }
            }
        }
    }

    synchronized public static WifiScanner.ScanData[] getScanResults(boolean flush) {
        synchronized (mLock) {
            if (isHalStarted()) {
                return getScanResultsNative(sWlan0Index, flush);
            } else {
                return null;
            }
        }
    }

    public static interface HotlistEventHandler {
        void onHotlistApFound (ScanResult[] result);
        void onHotlistApLost  (ScanResult[] result);
    }

    private static int sHotlistCmdId = 0;
    private static HotlistEventHandler sHotlistEventHandler;

    private native static boolean setHotlistNative(int iface, int id,
            WifiScanner.HotlistSettings settings);
    private native static boolean resetHotlistNative(int iface, int id);

    synchronized public static boolean setHotlist(WifiScanner.HotlistSettings settings,
                                    HotlistEventHandler eventHandler) {
        synchronized (mLock) {
            if (isHalStarted()) {
                if (sHotlistCmdId != 0) {
                    return false;
                } else {
                    sHotlistCmdId = getNewCmdIdLocked();
                }

                sHotlistEventHandler = eventHandler;
                if (setHotlistNative(sWlan0Index, sHotlistCmdId, settings) == false) {
                    sHotlistEventHandler = null;
                    return false;
                }

                return true;
            } else {
                return false;
            }
        }
    }

    synchronized public static void resetHotlist() {
        synchronized (mLock) {
            if (isHalStarted()) {
                if (sHotlistCmdId != 0) {
                    resetHotlistNative(sWlan0Index, sHotlistCmdId);
                    sHotlistCmdId = 0;
                    sHotlistEventHandler = null;
                }
            }
        }
    }

    synchronized public static void onHotlistApFound(int id, ScanResult[] results) {
        synchronized (mLock) {
            if (isHalStarted()) {
                if (sHotlistCmdId != 0) {
                    sHotlistEventHandler.onHotlistApFound(results);
                } else {
                /* this can happen because of race conditions */
                    Log.d(TAG, "Ignoring hotlist AP found event");
                }
            }
        }
    }

    synchronized public static void onHotlistApLost(int id, ScanResult[] results) {
        synchronized (mLock) {
            if (isHalStarted()) {
                if (sHotlistCmdId != 0) {
                    sHotlistEventHandler.onHotlistApLost(results);
                } else {
                /* this can happen because of race conditions */
                    Log.d(TAG, "Ignoring hotlist AP lost event");
                }
            }
        }
    }

    public static interface SignificantWifiChangeEventHandler {
        void onChangesFound(ScanResult[] result);
    }

    private static SignificantWifiChangeEventHandler sSignificantWifiChangeHandler;
    private static int sSignificantWifiChangeCmdId;

    private static native boolean trackSignificantWifiChangeNative(
            int iface, int id, WifiScanner.WifiChangeSettings settings);
    private static native boolean untrackSignificantWifiChangeNative(int iface, int id);

    synchronized public static boolean trackSignificantWifiChange(
            WifiScanner.WifiChangeSettings settings, SignificantWifiChangeEventHandler handler) {
        synchronized (mLock) {
            if (isHalStarted()) {
                if (sSignificantWifiChangeCmdId != 0) {
                    return false;
                } else {
                    sSignificantWifiChangeCmdId = getNewCmdIdLocked();
                }

                sSignificantWifiChangeHandler = handler;
                if (trackSignificantWifiChangeNative(sWlan0Index, sScanCmdId, settings) == false) {
                    sSignificantWifiChangeHandler = null;
                    return false;
                }

                return true;
            } else {
                return false;
            }

        }
    }

    synchronized static void untrackSignificantWifiChange() {
        synchronized (mLock) {
            if (isHalStarted()) {
                if (sSignificantWifiChangeCmdId != 0) {
                    untrackSignificantWifiChangeNative(sWlan0Index, sSignificantWifiChangeCmdId);
                    sSignificantWifiChangeCmdId = 0;
                    sSignificantWifiChangeHandler = null;
                }
            }
        }
    }

    synchronized static void onSignificantWifiChange(int id, ScanResult[] results) {
        synchronized (mLock) {
            if (sSignificantWifiChangeCmdId != 0) {
                sSignificantWifiChangeHandler.onChangesFound(results);
            } else {
            /* this can happen because of race conditions */
                Log.d(TAG, "Ignoring significant wifi change");
            }
        }
    }

    synchronized public static WifiLinkLayerStats getWifiLinkLayerStats(String iface) {
        // TODO: use correct iface name to Index translation
        if (iface == null) return null;
        synchronized (mLock) {
            if (isHalStarted()) {
                return getWifiLinkLayerStatsNative(sWlan0Index);
            } else {
                return null;
            }
        }
    }

    synchronized public static void setWifiLinkLayerStats(String iface, int enable) {
        if (iface == null) return;
        synchronized (mLock) {
            if (isHalStarted()) {
                setWifiLinkLayerStatsNative(sWlan0Index, enable);
            }
        }
    }

    public static native int getSupportedFeatureSetNative(int iface);
    synchronized public static int getSupportedFeatureSet() {
        synchronized (mLock) {
            if (isHalStarted()) {
                return getSupportedFeatureSetNative(sWlan0Index);
            } else {
                Log.d(TAG, "Failing getSupportedFeatureset because HAL isn't started");
                return 0;
            }
        }
    }

    /* Rtt related commands/events */
    public static interface RttEventHandler {
        void onRttResults(RttManager.RttResult[] result);
    }

    private static RttEventHandler sRttEventHandler;
    private static int sRttCmdId;

    synchronized private static void onRttResults(int id, RttManager.RttResult[] results) {
        if (id == sRttCmdId) {
            Log.d(TAG, "Received " + results.length + " rtt results");
            sRttEventHandler.onRttResults(results);
            sRttCmdId = 0;
        } else {
            Log.d(TAG, "RTT Received event for unknown cmd = " + id + ", current id = " + sRttCmdId);
        }
    }

    private static native boolean requestRangeNative(
            int iface, int id, RttManager.RttParams[] params);
    private static native boolean cancelRangeRequestNative(
            int iface, int id, RttManager.RttParams[] params);

    synchronized public static boolean requestRtt(
            RttManager.RttParams[] params, RttEventHandler handler) {
        synchronized (mLock) {
            if (isHalStarted()) {
                if (sRttCmdId != 0) {
                    Log.v("TAG", "Last one is still under measurement!");
                    return false;
                } else {
                    sRttCmdId = getNewCmdIdLocked();
                }
                sRttEventHandler = handler;
                Log.v(TAG, "native issue RTT request");
                return requestRangeNative(sWlan0Index, sRttCmdId, params);
            } else {
                return false;
            }
        }
    }

    synchronized public static boolean cancelRtt(RttManager.RttParams[] params) {
        synchronized(mLock) {
            if (isHalStarted()) {
                if (sRttCmdId == 0) {
                    return false;
                }

                sRttCmdId = 0;

                if (cancelRangeRequestNative(sWlan0Index, sRttCmdId, params)) {
                    sRttEventHandler = null;
                    Log.v(TAG, "RTT cancel Request Successfully");
                    return true;
                } else {
                    Log.e(TAG, "RTT cancel Request failed");
                    return false;
                }
            } else {
                return false;
            }
        }
    }

    private static native boolean setScanningMacOuiNative(int iface, byte[] oui);

    synchronized public static boolean setScanningMacOui(byte[] oui) {
        synchronized (mLock) {
            if (isHalStarted()) {
                return setScanningMacOuiNative(sWlan0Index, oui);
            } else {
                return false;
            }
        }
    }

    private static native int[] getChannelsForBandNative(
            int iface, int band);

    synchronized public static int [] getChannelsForBand(int band) {
        synchronized (mLock) {
            if (isHalStarted()) {
                return getChannelsForBandNative(sWlan0Index, band);
	    } else {
                return null;
            }
        }
    }

    private static native boolean isGetChannelsForBandSupportedNative();
    synchronized public static boolean isGetChannelsForBandSupported(){
        synchronized (mLock) {
            if (isHalStarted()) {
                return isGetChannelsForBandSupportedNative();
	    } else {
                return false;
            }
        }
    }

    private static native boolean setDfsFlagNative(int iface, boolean dfsOn);
    synchronized public static boolean setDfsFlag(boolean dfsOn) {
        synchronized (mLock) {
            if (isHalStarted()) {
                return setDfsFlagNative(sWlan0Index, dfsOn);
            } else {
                return false;
            }
        }
    }

    private static native boolean toggleInterfaceNative(int on);
    synchronized public static boolean toggleInterface(int on) {
        synchronized (mLock) {
            if (isHalStarted()) {
                return toggleInterfaceNative(0);
            } else {
                return false;
            }
        }
    }

    private static native RttManager.RttCapabilities getRttCapabilitiesNative(int iface);
    synchronized public static RttManager.RttCapabilities getRttCapabilities() {
        synchronized (mLock) {
            if (isHalStarted()) {
                return getRttCapabilitiesNative(sWlan0Index);
            }else {
                return null;
            }
        }
    }

    private static native boolean setCountryCodeHalNative(int iface, String CountryCode);
    synchronized public static boolean setCountryCodeHal( String CountryCode) {
        synchronized (mLock) {
            if (isHalStarted()) {
                return setCountryCodeHalNative(sWlan0Index, CountryCode);
            } else {
                return false;
            }
        }
    }

    /* Rtt related commands/events */
    public abstract class TdlsEventHandler {
        abstract public void onTdlsStatus(String macAddr, int status, int reason);
    }

    private static TdlsEventHandler sTdlsEventHandler;

    private static native boolean enableDisableTdlsNative(int iface, boolean enable,
            String macAddr);
    synchronized public static boolean enableDisableTdls(boolean enable, String macAdd,
            TdlsEventHandler tdlsCallBack) {
        synchronized (mLock) {
            sTdlsEventHandler = tdlsCallBack;
            return enableDisableTdlsNative(sWlan0Index, enable, macAdd);
        }
    }

    // Once TDLS per mac and event feature is implemented, this class definition should be
    // moved to the right place, like WifiManager etc
    public static class TdlsStatus {
        int channel;
        int global_operating_class;
        int state;
        int reason;
    }
    private static native TdlsStatus getTdlsStatusNative(int iface, String macAddr);
    synchronized public static TdlsStatus getTdlsStatus (String macAdd) {
        synchronized (mLock) {
            if (isHalStarted()) {
                return getTdlsStatusNative(sWlan0Index, macAdd);
            } else {
                return null;
            }
        }
    }

    //ToFix: Once TDLS per mac and event feature is implemented, this class definition should be
    // moved to the right place, like WifiStateMachine etc
    public static class TdlsCapabilities {
        /* Maximum TDLS session number can be supported by the Firmware and hardware */
        int maxConcurrentTdlsSessionNumber;
        boolean isGlobalTdlsSupported;
        boolean isPerMacTdlsSupported;
        boolean isOffChannelTdlsSupported;
    }



    private static native TdlsCapabilities getTdlsCapabilitiesNative(int iface);
    synchronized public static TdlsCapabilities getTdlsCapabilities () {
        synchronized (mLock) {
            if (isHalStarted()) {
                return getTdlsCapabilitiesNative(sWlan0Index);
            } else {
                return null;
            }
        }
    }

    synchronized private static boolean onTdlsStatus(String macAddr, int status, int reason) {
         if (sTdlsEventHandler == null) {
             return false;
         } else {
             sTdlsEventHandler.onTdlsStatus(macAddr, status, reason);
             return true;
         }
    }

    //---------------------------------------------------------------------------------

    /* Wifi Logger commands/events */

    public static native boolean startLogging(int iface);

    public static interface WifiLoggerEventHandler {
        void onRingBufferData(RingBufferStatus status, byte[] buffer);
        void onWifiAlert(int errorCode, byte[] buffer);
    }

    private static WifiLoggerEventHandler sWifiLoggerEventHandler = null;

    private static void onRingBufferData(RingBufferStatus status, byte[] buffer) {
        if (sWifiLoggerEventHandler != null)
            sWifiLoggerEventHandler.onRingBufferData(status, buffer);
    }

    private static void onWifiAlert(byte[] buffer, int errorCode) {
        if (sWifiLoggerEventHandler != null)
            sWifiLoggerEventHandler.onWifiAlert(errorCode, buffer);
    }

    private static int sLogCmdId = -1;
    private static native boolean setLoggingEventHandlerNative(int iface, int id);
    synchronized public static boolean setLoggingEventHandler(WifiLoggerEventHandler handler) {
        synchronized (mLock) {
            if (isHalStarted()) {
                int oldId =  sLogCmdId;
                sLogCmdId = getNewCmdIdLocked();
                if (!setLoggingEventHandlerNative(sWlan0Index, sLogCmdId)) {
                    sLogCmdId = oldId;
                    return false;
                }
                sWifiLoggerEventHandler = handler;
                return true;
            } else {
                return false;
            }
        }
    }

    private static native boolean startLoggingRingBufferNative(int iface, int verboseLevel,
            int flags, int minIntervalSec ,int minDataSize, String ringName);
    synchronized public static boolean startLoggingRingBuffer(int verboseLevel, int flags, int maxInterval,
            int minDataSize, String ringName){
        synchronized (mLock) {
            if (isHalStarted()) {
                return startLoggingRingBufferNative(sWlan0Index, verboseLevel, flags, maxInterval,
                        minDataSize, ringName);
            } else {
                return false;
            }
        }
    }

    private static native int getSupportedLoggerFeatureSetNative(int iface);
    synchronized public static int getSupportedLoggerFeatureSet() {
        synchronized (mLock) {
            if (isHalStarted()) {
                return getSupportedLoggerFeatureSetNative(sWlan0Index);
            } else {
                return 0;
            }
        }
    }

    private static native boolean resetLogHandlerNative(int iface, int id);
    synchronized public static boolean resetLogHandler() {
        synchronized (mLock) {
            if (isHalStarted()) {
                if (sLogCmdId == -1) {
                    Log.e(TAG,"Can not reset handler Before set any handler");
                    return false;
                }
                sWifiLoggerEventHandler = null;
                if (resetLogHandlerNative(sWlan0Index, sLogCmdId)) {
                    sLogCmdId = -1;
                    return true;
                } else {
                    return false;
                }
            } else {
                return false;
            }
        }
    }

    private static native String getDriverVersionNative(int iface);
    synchronized public static String getDriverVersion() {
        synchronized (mLock) {
            if (isHalStarted()) {
                return getDriverVersionNative(sWlan0Index);
            } else {
                return "";
            }
        }
    }


    private static native String getFirmwareVersionNative(int iface);
    synchronized public static String getFirmwareVersion() {
        synchronized (mLock) {
            if (isHalStarted()) {
                return getFirmwareVersionNative(sWlan0Index);
            } else {
                return "";
            }
        }
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

        @Override
        public String toString() {
            return "name: " + name + " flag: " + flag + " ringBufferId: " + ringBufferId +
                    " ringBufferByteSize: " +ringBufferByteSize + " verboseLevel: " +verboseLevel +
                    " writtenBytes: " + writtenBytes + " readBytes: " + readBytes +
                    " writtenRecords: " + writtenRecords;
        }
    }

    private static native RingBufferStatus[] getRingBufferStatusNative(int iface);
    synchronized public static RingBufferStatus[] getRingBufferStatus() {
        synchronized (mLock) {
            if (isHalStarted()) {
                return getRingBufferStatusNative(sWlan0Index);
            } else {
                return null;
            }
        }
    }

    private static native boolean getRingBufferDataNative(int iface, String ringName);
    synchronized public static boolean getRingBufferData(String ringName) {
        synchronized (mLock) {
            if (isHalStarted()) {
                return getRingBufferDataNative(sWlan0Index, ringName);
            } else {
                return false;
            }
        }
    }

    static private byte[] mFwMemoryDump;
    private static void onWifiFwMemoryAvailable(byte[] buffer) {
        mFwMemoryDump = buffer;
        if (DBG) {
            Log.d(TAG, "onWifiFwMemoryAvailable is called and buffer length is: " +
                    (buffer == null ? 0 :  buffer.length));
        }
    }

    private static native boolean getFwMemoryDumpNative(int iface);
    synchronized public static byte[] getFwMemoryDump() {
        synchronized (mLock) {
            if (isHalStarted()) {
                if(getFwMemoryDumpNative(sWlan0Index)) {
                    byte[] fwMemoryDump = mFwMemoryDump;
                    mFwMemoryDump = null;
                    return fwMemoryDump;
                } else {
                    return null;
                }
            }

            return null;
        }
    }

    //---------------------------------------------------------------------------------
    /* Configure ePNO */

    public class WifiPnoNetwork {
        String SSID;
        int rssi_threshold;
        int flags;
        int auth;
        String configKey; // kept for reference

        WifiPnoNetwork(WifiConfiguration config, int threshold) {
            if (config.SSID == null) {
                this.SSID = "";
                this.flags = 1;
            } else {
                this.SSID = config.SSID;
            }
            this.rssi_threshold = threshold;
            if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) {
                auth |= 2;
            } else if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP) ||
                    config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X)) {
                auth |= 4;
            } else if (config.wepKeys[0] != null) {
                auth |= 1;
            } else {
                auth |= 1;
            }
//            auth = 0;
            flags |= 6; //A and G
            configKey = config.configKey();
        }

        @Override
        public String toString() {
            StringBuilder sbuf = new StringBuilder();
            sbuf.append(this.SSID);
            sbuf.append(" flags=").append(this.flags);
            sbuf.append(" rssi=").append(this.rssi_threshold);
            sbuf.append(" auth=").append(this.auth);
            return sbuf.toString();
        }
    }

    public static interface WifiPnoEventHandler {
        void onPnoNetworkFound(ScanResult results[]);
    }

    private static WifiPnoEventHandler sWifiPnoEventHandler;

    private static int sPnoCmdId = 0;

    private native static boolean setPnoListNative(int iface, int id, WifiPnoNetwork list[]);

    synchronized public static boolean setPnoList(WifiPnoNetwork list[],
                                                  WifiPnoEventHandler eventHandler) {
        Log.e(TAG, "setPnoList cmd " + sPnoCmdId);

        synchronized (mLock) {
            if (isHalStarted()) {

                sPnoCmdId = getNewCmdIdLocked();

                sWifiPnoEventHandler = eventHandler;
                if (setPnoListNative(sWlan0Index, sPnoCmdId, list)) {
                    return true;
                }
            }

            sWifiPnoEventHandler = null;
            return false;
        }
    }

    synchronized public static void onPnoNetworkFound(int id, ScanResult[] results) {

        if (results == null) {
            Log.e(TAG, "onPnoNetworkFound null results");
            return;

        }
        Log.d(TAG, "WifiNative.onPnoNetworkFound result " + results.length);

        //Log.e(TAG, "onPnoNetworkFound length " + results.length);
        //return;
        for (int i=0; i<results.length; i++) {
            Log.e(TAG, "onPnoNetworkFound SSID " + results[i].SSID
                    + " " + results[i].level + " " + results[i].frequency);

            populateScanResult(results[i], results[i].bytes, "onPnoNetworkFound ");
            results[i].wifiSsid = WifiSsid.createFromAsciiEncoded(results[i].SSID);
        }
        synchronized (mLock) {
            if (sPnoCmdId != 0 && sWifiPnoEventHandler != null) {
                sWifiPnoEventHandler.onPnoNetworkFound(results);
            } else {
                /* this can happen because of race conditions */
                Log.d(TAG, "Ignoring Pno Network found event");
            }
        }
    }

    public class WifiLazyRoamParams {
        int A_band_boost_threshold;
        int A_band_penalty_threshold;
        int A_band_boost_factor;
        int A_band_penalty_factor;
        int A_band_max_boost;
        int lazy_roam_hysteresis;
        int alert_roam_rssi_trigger;

        WifiLazyRoamParams() {
        }

        @Override
        public String toString() {
            StringBuilder sbuf = new StringBuilder();
            sbuf.append(" A_band_boost_threshold=").append(this.A_band_boost_threshold);
            sbuf.append(" A_band_penalty_threshold=").append(this.A_band_penalty_threshold);
            sbuf.append(" A_band_boost_factor=").append(this.A_band_boost_factor);
            sbuf.append(" A_band_penalty_factor=").append(this.A_band_penalty_factor);
            sbuf.append(" A_band_max_boost=").append(this.A_band_max_boost);
            sbuf.append(" lazy_roam_hysteresis=").append(this.lazy_roam_hysteresis);
            sbuf.append(" alert_roam_rssi_trigger=").append(this.alert_roam_rssi_trigger);
            return sbuf.toString();
        }
    }

    private native static boolean setLazyRoamNative(int iface, int id,
                                              boolean enabled, WifiLazyRoamParams param);

    synchronized public static boolean setLazyRoam(boolean enabled, WifiLazyRoamParams params) {
        synchronized (mLock) {
            if (isHalStarted()) {
                sPnoCmdId = getNewCmdIdLocked();
                return setLazyRoamNative(sWlan0Index, sPnoCmdId, enabled, params);
            } else {
                return false;
            }
        }
    }

    private native static boolean setBssidBlacklistNative(int iface, int id,
                                              String list[]);

    synchronized public static boolean setBssidBlacklist(String list[]) {
        int size = 0;
        if (list != null) {
            size = list.length;
        }
        Log.e(TAG, "setBssidBlacklist cmd " + sPnoCmdId + " size " + size);

        synchronized (mLock) {
            if (isHalStarted()) {
                sPnoCmdId = getNewCmdIdLocked();
                return setBssidBlacklistNative(sWlan0Index, sPnoCmdId, list);
            } else {
                return false;
            }
        }
    }

    private native static boolean setSsidWhitelistNative(int iface, int id, String list[]);

    synchronized public static boolean setSsidWhitelist(String list[]) {
        int size = 0;
        if (list != null) {
            size = list.length;
        }
        Log.e(TAG, "setSsidWhitelist cmd " + sPnoCmdId + " size " + size);

        synchronized (mLock) {
            if (isHalStarted()) {
                sPnoCmdId = getNewCmdIdLocked();

                return setSsidWhitelistNative(sWlan0Index, sPnoCmdId, list);
            } else {
                return false;
            }
        }
    }
}
