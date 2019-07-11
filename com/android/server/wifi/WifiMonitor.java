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

import android.net.NetworkInfo;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pProvDiscEvent;
import android.net.wifi.p2p.nsd.WifiP2pServiceResponse;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.wifi.p2p.WifiP2pServiceImpl.P2pStatus;

import com.android.internal.util.Protocol;
import com.android.internal.util.StateMachine;

import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Listens for events from the wpa_supplicant server, and passes them on
 * to the {@link StateMachine} for handling. Runs in its own thread.
 *
 * @hide
 */
public class WifiMonitor {

    private static boolean DBG = false;
    private static final boolean VDBG = false;
    private static final String TAG = "WifiMonitor";

    /** Events we receive from the supplicant daemon */

    private static final int CONNECTED    = 1;
    private static final int DISCONNECTED = 2;
    private static final int STATE_CHANGE = 3;
    private static final int SCAN_RESULTS = 4;
    private static final int LINK_SPEED   = 5;
    private static final int TERMINATING  = 6;
    private static final int DRIVER_STATE = 7;
    private static final int EAP_FAILURE  = 8;
    private static final int ASSOC_REJECT = 9;
    private static final int SSID_TEMP_DISABLE = 10;
    private static final int SSID_REENABLE = 11;
    private static final int BSS_ADDED = 12;
    private static final int BSS_REMOVED = 13;
    private static final int UNKNOWN      = 14;

    /** All events coming from the supplicant start with this prefix */
    private static final String EVENT_PREFIX_STR = "CTRL-EVENT-";
    private static final int EVENT_PREFIX_LEN_STR = EVENT_PREFIX_STR.length();

    /** All events coming from the supplicant start with this prefix */
    private static final String REQUEST_PREFIX_STR = "CTRL-REQ-";
    private static final int REQUEST_PREFIX_LEN_STR = REQUEST_PREFIX_STR.length();


    /** All WPA events coming from the supplicant start with this prefix */
    private static final String WPA_EVENT_PREFIX_STR = "WPA:";
    private static final String PASSWORD_MAY_BE_INCORRECT_STR =
       "pre-shared key may be incorrect";

    /* WPS events */
    private static final String WPS_SUCCESS_STR = "WPS-SUCCESS";

    /* Format: WPS-FAIL msg=%d [config_error=%d] [reason=%d (%s)] */
    private static final String WPS_FAIL_STR    = "WPS-FAIL";
    private static final String WPS_FAIL_PATTERN =
            "WPS-FAIL msg=\\d+(?: config_error=(\\d+))?(?: reason=(\\d+))?";

    /* config error code values for config_error=%d */
    private static final int CONFIG_MULTIPLE_PBC_DETECTED = 12;
    private static final int CONFIG_AUTH_FAILURE = 18;

    /* reason code values for reason=%d */
    private static final int REASON_TKIP_ONLY_PROHIBITED = 1;
    private static final int REASON_WEP_PROHIBITED = 2;

    private static final String WPS_OVERLAP_STR = "WPS-OVERLAP-DETECTED";
    private static final String WPS_TIMEOUT_STR = "WPS-TIMEOUT";

    /* Hotspot 2.0 ANQP query events */
    private static final String GAS_QUERY_PREFIX_STR = "GAS-QUERY-";
    private static final String GAS_QUERY_START_STR = "GAS-QUERY-START";
    private static final String GAS_QUERY_DONE_STR = "GAS-QUERY-DONE";
    private static final String RX_HS20_ANQP_ICON_STR = "RX-HS20-ANQP-ICON";
    private static final int RX_HS20_ANQP_ICON_STR_LEN = RX_HS20_ANQP_ICON_STR.length();

    /* Hotspot 2.0 events */
    private static final String HS20_PREFIX_STR = "HS20-";
    private static final String HS20_SUB_REM_STR = "HS20-SUBSCRIPTION-REMEDIATION";
    private static final String HS20_DEAUTH_STR = "HS20-DEAUTH-IMMINENT-NOTICE";

    private static final String IDENTITY_STR = "IDENTITY";

    private static final String SIM_STR = "SIM";


    //used to debug and detect if we miss an event
    private static int eventLogCounter = 0;

    /**
     * Names of events from wpa_supplicant (minus the prefix). In the
     * format descriptions, * &quot;<code>x</code>&quot;
     * designates a dynamic value that needs to be parsed out from the event
     * string
     */
    /**
     * <pre>
     * CTRL-EVENT-CONNECTED - Connection to xx:xx:xx:xx:xx:xx completed
     * </pre>
     * <code>xx:xx:xx:xx:xx:xx</code> is the BSSID of the associated access point
     */
    private static final String CONNECTED_STR =    "CONNECTED";
    /**
     * <pre>
     * CTRL-EVENT-DISCONNECTED - Disconnect event - remove keys
     * </pre>
     */
    private static final String DISCONNECTED_STR = "DISCONNECTED";
    /**
     * <pre>
     * CTRL-EVENT-STATE-CHANGE x
     * </pre>
     * <code>x</code> is the numerical value of the new state.
     */
    private static final String STATE_CHANGE_STR =  "STATE-CHANGE";
    /**
     * <pre>
     * CTRL-EVENT-SCAN-RESULTS ready
     * </pre>
     */
    private static final String SCAN_RESULTS_STR =  "SCAN-RESULTS";

    /**
     * <pre>
     * CTRL-EVENT-LINK-SPEED x Mb/s
     * </pre>
     * {@code x} is the link speed in Mb/sec.
     */
    private static final String LINK_SPEED_STR = "LINK-SPEED";
    /**
     * <pre>
     * CTRL-EVENT-TERMINATING - signal x
     * </pre>
     * <code>x</code> is the signal that caused termination.
     */
    private static final String TERMINATING_STR =  "TERMINATING";
    /**
     * <pre>
     * CTRL-EVENT-DRIVER-STATE state
     * </pre>
     * <code>state</code> can be HANGED
     */
    private static final String DRIVER_STATE_STR = "DRIVER-STATE";
    /**
     * <pre>
     * CTRL-EVENT-EAP-FAILURE EAP authentication failed
     * </pre>
     */
    private static final String EAP_FAILURE_STR = "EAP-FAILURE";

    /**
     * This indicates an authentication failure on EAP FAILURE event
     */
    private static final String EAP_AUTH_FAILURE_STR = "EAP authentication failed";

    /**
     * This indicates an assoc reject event
     */
    private static final String ASSOC_REJECT_STR = "ASSOC-REJECT";

    /**
     * This indicates auth or association failure bad enough so as network got disabled
     * - WPA_PSK auth failure suspecting shared key mismatch
     * - failed multiple Associations
     */
    private static final String TEMP_DISABLED_STR = "SSID-TEMP-DISABLED";

    /**
     * This indicates a previously disabled SSID was reenabled by supplicant
     */
    private static final String REENABLED_STR = "SSID-REENABLED";

    /**
     * This indicates supplicant found a given BSS
     */
    private static final String BSS_ADDED_STR = "BSS-ADDED";

    /**
     * This indicates supplicant removed a given BSS
     */
    private static final String BSS_REMOVED_STR = "BSS-REMOVED";

    /**
     * Regex pattern for extracting an Ethernet-style MAC address from a string.
     * Matches a strings like the following:<pre>
     * CTRL-EVENT-CONNECTED - Connection to 00:1e:58:ec:d5:6d completed (reauth) [id=1 id_str=]</pre>
     */
    private static Pattern mConnectedEventPattern =
        Pattern.compile("((?:[0-9a-f]{2}:){5}[0-9a-f]{2}) .* \\[id=([0-9]+) ");

    /**
     * Regex pattern for extracting an Ethernet-style MAC address from a string.
     * Matches a strings like the following:<pre>
     * CTRL-EVENT-DISCONNECTED - bssid=ac:22:0b:24:70:74 reason=3 locally_generated=1
     */
    private static Pattern mDisconnectedEventPattern =
            Pattern.compile("((?:[0-9a-f]{2}:){5}[0-9a-f]{2}) +" +
                    "reason=([0-9]+) +locally_generated=([0-1])");

    /**
     * Regex pattern for extracting an Ethernet-style MAC address from a string.
     * Matches a strings like the following:<pre>
     * CTRL-EVENT-ASSOC-REJECT - bssid=ac:22:0b:24:70:74 status_code=1
     */
    private static Pattern mAssocRejectEventPattern =
            Pattern.compile("((?:[0-9a-f]{2}:){5}[0-9a-f]{2}) +" +
                    "status_code=([0-9]+)");

    /**
     * Regex pattern for extracting an Ethernet-style MAC address from a string.
     * Matches a strings like the following:<pre>
     * IFNAME=wlan0 Trying to associate with 6c:f3:7f:ae:87:71
     */
    private static final String TARGET_BSSID_STR =  "Trying to associate with ";

    private static Pattern mTargetBSSIDPattern =
            Pattern.compile("Trying to associate with ((?:[0-9a-f]{2}:){5}[0-9a-f]{2}).*");

    /**
     * Regex pattern for extracting an Ethernet-style MAC address from a string.
     * Matches a strings like the following:<pre>
     * IFNAME=wlan0 Associated with 6c:f3:7f:ae:87:71
     */
    private static final String ASSOCIATED_WITH_STR =  "Associated with ";

    private static Pattern mAssociatedPattern =
            Pattern.compile("Associated with ((?:[0-9a-f]{2}:){5}[0-9a-f]{2}).*");

    /**
     * Regex pattern for extracting SSIDs from request identity string.
     * Matches a strings like the following:<pre>
     * CTRL-REQ-SIM-<network id>:GSM-AUTH:<RAND1>:<RAND2>[:<RAND3>] needed for SSID <SSID>
     * This pattern should find
     *    0 - id
     *    1 - Rand1
     *    2 - Rand2
     *    3 - Rand3
     *    4 - SSID
     */
    private static Pattern mRequestGsmAuthPattern =
            Pattern.compile("SIM-([0-9]*):GSM-AUTH((:[0-9a-f]+)+) needed for SSID (.+)");

    /**
     * Regex pattern for extracting SSIDs from request identity string.
     * Matches a strings like the following:<pre>
     * CTRL-REQ-IDENTITY-xx:Identity needed for SSID XXXX</pre>
     */
    private static Pattern mRequestIdentityPattern =
            Pattern.compile("IDENTITY-([0-9]+):Identity needed for SSID (.+)");

    /** P2P events */
    private static final String P2P_EVENT_PREFIX_STR = "P2P";

    /* P2P-DEVICE-FOUND fa:7b:7a:42:02:13 p2p_dev_addr=fa:7b:7a:42:02:13 pri_dev_type=1-0050F204-1
       name='p2p-TEST1' config_methods=0x188 dev_capab=0x27 group_capab=0x0 */
    private static final String P2P_DEVICE_FOUND_STR = "P2P-DEVICE-FOUND";

    /* P2P-DEVICE-LOST p2p_dev_addr=42:fc:89:e1:e2:27 */
    private static final String P2P_DEVICE_LOST_STR = "P2P-DEVICE-LOST";

    /* P2P-FIND-STOPPED */
    private static final String P2P_FIND_STOPPED_STR = "P2P-FIND-STOPPED";

    /* P2P-GO-NEG-REQUEST 42:fc:89:a8:96:09 dev_passwd_id=4 */
    private static final String P2P_GO_NEG_REQUEST_STR = "P2P-GO-NEG-REQUEST";

    private static final String P2P_GO_NEG_SUCCESS_STR = "P2P-GO-NEG-SUCCESS";

    /* P2P-GO-NEG-FAILURE status=x */
    private static final String P2P_GO_NEG_FAILURE_STR = "P2P-GO-NEG-FAILURE";

    private static final String P2P_GROUP_FORMATION_SUCCESS_STR =
            "P2P-GROUP-FORMATION-SUCCESS";

    private static final String P2P_GROUP_FORMATION_FAILURE_STR =
            "P2P-GROUP-FORMATION-FAILURE";

    /* P2P-GROUP-STARTED p2p-wlan0-0 [client|GO] ssid="DIRECT-W8" freq=2437
       [psk=2182b2e50e53f260d04f3c7b25ef33c965a3291b9b36b455a82d77fd82ca15bc|passphrase="fKG4jMe3"]
       go_dev_addr=fa:7b:7a:42:02:13 [PERSISTENT] */
    private static final String P2P_GROUP_STARTED_STR = "P2P-GROUP-STARTED";

    /* P2P-GROUP-REMOVED p2p-wlan0-0 [client|GO] reason=REQUESTED */
    private static final String P2P_GROUP_REMOVED_STR = "P2P-GROUP-REMOVED";

    /* P2P-INVITATION-RECEIVED sa=fa:7b:7a:42:02:13 go_dev_addr=f8:7b:7a:42:02:13
        bssid=fa:7b:7a:42:82:13 unknown-network */
    private static final String P2P_INVITATION_RECEIVED_STR = "P2P-INVITATION-RECEIVED";

    /* P2P-INVITATION-RESULT status=1 */
    private static final String P2P_INVITATION_RESULT_STR = "P2P-INVITATION-RESULT";

    /* P2P-PROV-DISC-PBC-REQ 42:fc:89:e1:e2:27 p2p_dev_addr=42:fc:89:e1:e2:27
       pri_dev_type=1-0050F204-1 name='p2p-TEST2' config_methods=0x188 dev_capab=0x27
       group_capab=0x0 */
    private static final String P2P_PROV_DISC_PBC_REQ_STR = "P2P-PROV-DISC-PBC-REQ";

    /* P2P-PROV-DISC-PBC-RESP 02:12:47:f2:5a:36 */
    private static final String P2P_PROV_DISC_PBC_RSP_STR = "P2P-PROV-DISC-PBC-RESP";

    /* P2P-PROV-DISC-ENTER-PIN 42:fc:89:e1:e2:27 p2p_dev_addr=42:fc:89:e1:e2:27
       pri_dev_type=1-0050F204-1 name='p2p-TEST2' config_methods=0x188 dev_capab=0x27
       group_capab=0x0 */
    private static final String P2P_PROV_DISC_ENTER_PIN_STR = "P2P-PROV-DISC-ENTER-PIN";
    /* P2P-PROV-DISC-SHOW-PIN 42:fc:89:e1:e2:27 44490607 p2p_dev_addr=42:fc:89:e1:e2:27
       pri_dev_type=1-0050F204-1 name='p2p-TEST2' config_methods=0x188 dev_capab=0x27
       group_capab=0x0 */
    private static final String P2P_PROV_DISC_SHOW_PIN_STR = "P2P-PROV-DISC-SHOW-PIN";
    /* P2P-PROV-DISC-FAILURE p2p_dev_addr=42:fc:89:e1:e2:27 */
    private static final String P2P_PROV_DISC_FAILURE_STR = "P2P-PROV-DISC-FAILURE";

    /*
     * Protocol format is as follows.<br>
     * See the Table.62 in the WiFi Direct specification for the detail.
     * ______________________________________________________________
     * |           Length(2byte)     | Type(1byte) | TransId(1byte)}|
     * ______________________________________________________________
     * | status(1byte)  |            vendor specific(variable)      |
     *
     * P2P-SERV-DISC-RESP 42:fc:89:e1:e2:27 1 0300000101
     * length=3, service type=0(ALL Service), transaction id=1,
     * status=1(service protocol type not available)<br>
     *
     * P2P-SERV-DISC-RESP 42:fc:89:e1:e2:27 1 0300020201
     * length=3, service type=2(UPnP), transaction id=2,
     * status=1(service protocol type not available)
     *
     * P2P-SERV-DISC-RESP 42:fc:89:e1:e2:27 1 990002030010757569643a3131323
     * 2646534652d383537342d353961622d393332322d3333333435363738393034343a3
     * a75726e3a736368656d61732d75706e702d6f72673a736572766963653a436f6e746
     * 56e744469726563746f72793a322c757569643a36383539646564652d383537342d3
     * 53961622d393333322d3132333435363738393031323a3a75706e703a726f6f74646
     * 576696365
     * length=153,type=2(UPnP),transaction id=3,status=0
     *
     * UPnP Protocol format is as follows.
     * ______________________________________________________
     * |  Version (1)  |          USN (Variable)            |
     *
     * version=0x10(UPnP1.0) data=usn:uuid:1122de4e-8574-59ab-9322-33345678
     * 9044::urn:schemas-upnp-org:service:ContentDirectory:2,usn:uuid:6859d
     * ede-8574-59ab-9332-123456789012::upnp:rootdevice
     *
     * P2P-SERV-DISC-RESP 58:17:0c:bc:dd:ca 21 1900010200045f6970
     * 70c00c000c01094d795072696e746572c027
     * length=25, type=1(Bonjour),transaction id=2,status=0
     *
     * Bonjour Protocol format is as follows.
     * __________________________________________________________
     * |DNS Name(Variable)|DNS Type(1)|Version(1)|RDATA(Variable)|
     *
     * DNS Name=_ipp._tcp.local.,DNS type=12(PTR), Version=1,
     * RDATA=MyPrinter._ipp._tcp.local.
     *
     */
    private static final String P2P_SERV_DISC_RESP_STR = "P2P-SERV-DISC-RESP";

    private static final String HOST_AP_EVENT_PREFIX_STR = "AP";
    /* AP-STA-CONNECTED 42:fc:89:a8:96:09 dev_addr=02:90:4c:a0:92:54 */
    private static final String AP_STA_CONNECTED_STR = "AP-STA-CONNECTED";
    /* AP-STA-DISCONNECTED 42:fc:89:a8:96:09 */
    private static final String AP_STA_DISCONNECTED_STR = "AP-STA-DISCONNECTED";

    /* Supplicant events reported to a state machine */
    private static final int BASE = Protocol.BASE_WIFI_MONITOR;

    /* Connection to supplicant established */
    public static final int SUP_CONNECTION_EVENT                 = BASE + 1;
    /* Connection to supplicant lost */
    public static final int SUP_DISCONNECTION_EVENT              = BASE + 2;
   /* Network connection completed */
    public static final int NETWORK_CONNECTION_EVENT             = BASE + 3;
    /* Network disconnection completed */
    public static final int NETWORK_DISCONNECTION_EVENT          = BASE + 4;
    /* Scan results are available */
    public static final int SCAN_RESULTS_EVENT                   = BASE + 5;
    /* Supplicate state changed */
    public static final int SUPPLICANT_STATE_CHANGE_EVENT        = BASE + 6;
    /* Password failure and EAP authentication failure */
    public static final int AUTHENTICATION_FAILURE_EVENT         = BASE + 7;
    /* WPS success detected */
    public static final int WPS_SUCCESS_EVENT                    = BASE + 8;
    /* WPS failure detected */
    public static final int WPS_FAIL_EVENT                       = BASE + 9;
     /* WPS overlap detected */
    public static final int WPS_OVERLAP_EVENT                    = BASE + 10;
     /* WPS timeout detected */
    public static final int WPS_TIMEOUT_EVENT                    = BASE + 11;
    /* Driver was hung */
    public static final int DRIVER_HUNG_EVENT                    = BASE + 12;
    /* SSID was disabled due to auth failure or excessive
     * connection failures */
    public static final int SSID_TEMP_DISABLED                   = BASE + 13;
    /* SSID reenabled by supplicant */
    public static final int SSID_REENABLED                       = BASE + 14;

    /* Request Identity */
    public static final int SUP_REQUEST_IDENTITY                 = BASE + 15;

    /* Request SIM Auth */
    public static final int SUP_REQUEST_SIM_AUTH                 = BASE + 16;

    /* P2P events */
    public static final int P2P_DEVICE_FOUND_EVENT               = BASE + 21;
    public static final int P2P_DEVICE_LOST_EVENT                = BASE + 22;
    public static final int P2P_GO_NEGOTIATION_REQUEST_EVENT     = BASE + 23;
    public static final int P2P_GO_NEGOTIATION_SUCCESS_EVENT     = BASE + 25;
    public static final int P2P_GO_NEGOTIATION_FAILURE_EVENT     = BASE + 26;
    public static final int P2P_GROUP_FORMATION_SUCCESS_EVENT    = BASE + 27;
    public static final int P2P_GROUP_FORMATION_FAILURE_EVENT    = BASE + 28;
    public static final int P2P_GROUP_STARTED_EVENT              = BASE + 29;
    public static final int P2P_GROUP_REMOVED_EVENT              = BASE + 30;
    public static final int P2P_INVITATION_RECEIVED_EVENT        = BASE + 31;
    public static final int P2P_INVITATION_RESULT_EVENT          = BASE + 32;
    public static final int P2P_PROV_DISC_PBC_REQ_EVENT          = BASE + 33;
    public static final int P2P_PROV_DISC_PBC_RSP_EVENT          = BASE + 34;
    public static final int P2P_PROV_DISC_ENTER_PIN_EVENT        = BASE + 35;
    public static final int P2P_PROV_DISC_SHOW_PIN_EVENT         = BASE + 36;
    public static final int P2P_FIND_STOPPED_EVENT               = BASE + 37;
    public static final int P2P_SERV_DISC_RESP_EVENT             = BASE + 38;
    public static final int P2P_PROV_DISC_FAILURE_EVENT          = BASE + 39;

    /* hostap events */
    public static final int AP_STA_DISCONNECTED_EVENT            = BASE + 41;
    public static final int AP_STA_CONNECTED_EVENT               = BASE + 42;

    /* Indicates assoc reject event */
    public static final int ASSOCIATION_REJECTION_EVENT          = BASE + 43;

    /* hotspot 2.0 ANQP events */
    public static final int GAS_QUERY_START_EVENT                = BASE + 51;
    public static final int GAS_QUERY_DONE_EVENT                 = BASE + 52;
    public static final int RX_HS20_ANQP_ICON_EVENT              = BASE + 53;

    /* hotspot 2.0 events */
    public static final int HS20_REMEDIATION_EVENT               = BASE + 61;
    public static final int HS20_DEAUTH_EVENT                    = BASE + 62;

    /**
     * This indicates a read error on the monitor socket conenction
     */
    private static final String WPA_RECV_ERROR_STR = "recv error";

    /**
     * Max errors before we close supplicant connection
     */
    private static final int MAX_RECV_ERRORS    = 10;

    private final String mInterfaceName;
    private final WifiNative mWifiNative;
    private final StateMachine mStateMachine;
    private StateMachine mStateMachine2;
    private boolean mMonitoring;

    // This is a global counter, since it's not monitor specific. However, the existing
    // implementation forwards all "global" control events like CTRL-EVENT-TERMINATING
    // to the p2p0 monitor. Is that expected ? It seems a bit surprising.
    //
    // TODO: If the p2p0 monitor isn't registered, the behaviour is even more surprising.
    // The event will be dispatched to all monitors, and each of them will end up incrementing
    // it in their dispatchXXX method. If we have 5 registered monitors (say), 2 consecutive
    // recv errors will cause us to disconnect from the supplicant (instead of the intended 10).
    //
    // This variable is always accessed and modified under a WifiMonitorSingleton lock.
    private static int sRecvErrors;

    public WifiMonitor(StateMachine stateMachine, WifiNative wifiNative) {
        if (DBG) Log.d(TAG, "Creating WifiMonitor");
        mWifiNative = wifiNative;
        mInterfaceName = wifiNative.mInterfaceName;
        mStateMachine = stateMachine;
        mStateMachine2 = null;
        mMonitoring = false;

        WifiMonitorSingleton.sInstance.registerInterfaceMonitor(mInterfaceName, this);
    }

    void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            DBG = true;
        } else {
            DBG = false;
        }
    }

        // TODO: temporary hack, should be handle by supplicant manager (new component in future)
    public void setStateMachine2(StateMachine stateMachine) {
        mStateMachine2 = stateMachine;
    }

    public void startMonitoring() {
        WifiMonitorSingleton.sInstance.startMonitoring(mInterfaceName);
    }

    public void stopMonitoring() {
        WifiMonitorSingleton.sInstance.stopMonitoring(mInterfaceName);
    }

    public void stopSupplicant() {
        WifiMonitorSingleton.sInstance.stopSupplicant();
    }

    public void killSupplicant(boolean p2pSupported) {
        WifiMonitorSingleton.sInstance.killSupplicant(p2pSupported);
    }

    private static class WifiMonitorSingleton {
        private static final WifiMonitorSingleton sInstance = new WifiMonitorSingleton();

        private final HashMap<String, WifiMonitor> mIfaceMap = new HashMap<String, WifiMonitor>();
        private boolean mConnected = false;
        private WifiNative mWifiNative;

        private WifiMonitorSingleton() {
        }

        public synchronized void startMonitoring(String iface) {
            WifiMonitor m = mIfaceMap.get(iface);
            if (m == null) {
                Log.e(TAG, "startMonitor called with unknown iface=" + iface);
                return;
            }

            Log.d(TAG, "startMonitoring(" + iface + ") with mConnected = " + mConnected);

            if (mConnected) {
                m.mMonitoring = true;
                m.mStateMachine.sendMessage(SUP_CONNECTION_EVENT);
            } else {
                if (DBG) Log.d(TAG, "connecting to supplicant");
                int connectTries = 0;
                while (true) {
                    if (mWifiNative.connectToSupplicant()) {
                        m.mMonitoring = true;
                        m.mStateMachine.sendMessage(SUP_CONNECTION_EVENT);
                        new MonitorThread(mWifiNative, this).start();
                        mConnected = true;
                        break;
                    }
                    if (connectTries++ < 5) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignore) {
                        }
                    } else {
                        mIfaceMap.remove(iface);
                        m.mStateMachine.sendMessage(SUP_DISCONNECTION_EVENT);
                        Log.e(TAG, "startMonitoring(" + iface + ") failed!");
                        break;
                    }
                }
            }
        }

        public synchronized void stopMonitoring(String iface) {
            WifiMonitor m = mIfaceMap.get(iface);
            if (DBG) Log.d(TAG, "stopMonitoring(" + iface + ") = " + m.mStateMachine);
            m.mMonitoring = false;
            m.mStateMachine.sendMessage(SUP_DISCONNECTION_EVENT);
        }

        public synchronized void registerInterfaceMonitor(String iface, WifiMonitor m) {
            if (DBG) Log.d(TAG, "registerInterface(" + iface + "+" + m.mStateMachine + ")");
            mIfaceMap.put(iface, m);
            if (mWifiNative == null) {
                mWifiNative = m.mWifiNative;
            }
        }

        public synchronized void unregisterInterfaceMonitor(String iface) {
            // REVIEW: When should we call this? If this isn't called, then WifiMonitor
            // objects will remain in the mIfaceMap; and won't ever get deleted

            WifiMonitor m = mIfaceMap.remove(iface);
            if (DBG) Log.d(TAG, "unregisterInterface(" + iface + "+" + m.mStateMachine + ")");
        }

        public synchronized void stopSupplicant() {
            mWifiNative.stopSupplicant();
        }

        public synchronized void killSupplicant(boolean p2pSupported) {
            WifiNative.killSupplicant(p2pSupported);
            mConnected = false;
            for (WifiMonitor m : mIfaceMap.values()) {
                m.mMonitoring = false;
            }
        }

        private synchronized boolean dispatchEvent(String eventStr) {
            String iface;
            if (eventStr.startsWith("IFNAME=")) {
                int space = eventStr.indexOf(' ');
                if (space != -1) {
                    iface = eventStr.substring(7, space);
                    if (!mIfaceMap.containsKey(iface) && iface.startsWith("p2p-")) {
                        // p2p interfaces are created dynamically, but we have
                        // only one P2p state machine monitoring all of them; look
                        // for it explicitly, and send messages there ..
                        iface = "p2p0";
                    }
                    eventStr = eventStr.substring(space + 1);
                } else {
                    // No point dispatching this event to any interface, the dispatched
                    // event string will begin with "IFNAME=" which dispatchEvent can't really
                    // do anything about.
                    Log.e(TAG, "Dropping malformed event (unparsable iface): " + eventStr);
                    return false;
                }
            } else {
                // events without prefix belong to p2p0 monitor
                iface = "p2p0";
            }

            if (VDBG) Log.d(TAG, "Dispatching event to interface: " + iface);

            WifiMonitor m = mIfaceMap.get(iface);
            if (m != null) {
                if (m.mMonitoring) {
                    if (m.dispatchEvent(eventStr, iface)) {
                        mConnected = false;
                        return true;
                    }

                    return false;
                } else {
                    if (DBG) Log.d(TAG, "Dropping event because (" + iface + ") is stopped");
                    return false;
                }
            } else {
                if (DBG) Log.d(TAG, "Sending to all monitors because there's no matching iface");
                boolean done = false;
                for (WifiMonitor monitor : mIfaceMap.values()) {
                    if (monitor.mMonitoring && monitor.dispatchEvent(eventStr, iface)) {
                        done = true;
                    }
                }

                if (done) {
                    mConnected = false;
                }

                return done;
            }
        }
    }

    private static class MonitorThread extends Thread {
        private final WifiNative mWifiNative;
        private final WifiMonitorSingleton mWifiMonitorSingleton;

        public MonitorThread(WifiNative wifiNative, WifiMonitorSingleton wifiMonitorSingleton) {
            super("WifiMonitor");
            mWifiNative = wifiNative;
            mWifiMonitorSingleton = wifiMonitorSingleton;
        }

        public void run() {
            //noinspection InfiniteLoopStatement
            for (;;) {
                String eventStr = mWifiNative.waitForEvent();

                // Skip logging the common but mostly uninteresting scan-results event
                if (DBG && eventStr.indexOf(SCAN_RESULTS_STR) == -1) {
                    Log.d(TAG, "Event [" + eventStr + "]");
                }

                if (mWifiMonitorSingleton.dispatchEvent(eventStr)) {
                    if (DBG) Log.d(TAG, "Disconnecting from the supplicant, no more events");
                    break;
                }
            }
        }
    }

    private void logDbg(String debug) {
        Log.e(TAG, debug/*+ " stack:" + Thread.currentThread().getStackTrace()[2].getMethodName()
                +" - "+ Thread.currentThread().getStackTrace()[3].getMethodName()
                +" - "+ Thread.currentThread().getStackTrace()[4].getMethodName()
                +" - "+ Thread.currentThread().getStackTrace()[5].getMethodName()*/);
    }

    /* @return true if the event was supplicant disconnection */
    private boolean dispatchEvent(String eventStr, String iface) {

        if (DBG) {
            // Dont log CTRL-EVENT-BSS-ADDED which are too verbose and not handled
            if (eventStr != null && !eventStr.contains("CTRL-EVENT-BSS-ADDED")) {
                logDbg("WifiMonitor:" + iface + " cnt=" + Integer.toString(eventLogCounter)
                        + " dispatchEvent: " + eventStr);
            }
        }

        if (!eventStr.startsWith(EVENT_PREFIX_STR)) {
            if (eventStr.startsWith(WPA_EVENT_PREFIX_STR) &&
                    0 < eventStr.indexOf(PASSWORD_MAY_BE_INCORRECT_STR)) {
               mStateMachine.sendMessage(AUTHENTICATION_FAILURE_EVENT, eventLogCounter);
            } else if (eventStr.startsWith(WPS_SUCCESS_STR)) {
                mStateMachine.sendMessage(WPS_SUCCESS_EVENT);
            } else if (eventStr.startsWith(WPS_FAIL_STR)) {
                handleWpsFailEvent(eventStr);
            } else if (eventStr.startsWith(WPS_OVERLAP_STR)) {
                mStateMachine.sendMessage(WPS_OVERLAP_EVENT);
            } else if (eventStr.startsWith(WPS_TIMEOUT_STR)) {
                mStateMachine.sendMessage(WPS_TIMEOUT_EVENT);
            } else if (eventStr.startsWith(P2P_EVENT_PREFIX_STR)) {
                handleP2pEvents(eventStr);
            } else if (eventStr.startsWith(HOST_AP_EVENT_PREFIX_STR)) {
                handleHostApEvents(eventStr);
            } else if (eventStr.startsWith(GAS_QUERY_PREFIX_STR)) {
                handleGasQueryEvents(eventStr);
            } else if (eventStr.startsWith(RX_HS20_ANQP_ICON_STR)) {
                if (mStateMachine2 != null)
                    mStateMachine2.sendMessage(RX_HS20_ANQP_ICON_EVENT,
                            eventStr.substring(RX_HS20_ANQP_ICON_STR_LEN + 1));
            } else if (eventStr.startsWith(HS20_PREFIX_STR)) {
                handleHs20Events(eventStr);
            } else if (eventStr.startsWith(REQUEST_PREFIX_STR)) {
                handleRequests(eventStr);
            } else if (eventStr.startsWith(TARGET_BSSID_STR)) {
                handleTargetBSSIDEvent(eventStr);
            } else if (eventStr.startsWith(ASSOCIATED_WITH_STR)) {
                handleAssociatedBSSIDEvent(eventStr);
            } else {
                if (DBG) Log.w(TAG, "couldn't identify event type - " + eventStr);
            }
            eventLogCounter++;
            return false;
        }

        String eventName = eventStr.substring(EVENT_PREFIX_LEN_STR);
        int nameEnd = eventName.indexOf(' ');
        if (nameEnd != -1)
            eventName = eventName.substring(0, nameEnd);
        if (eventName.length() == 0) {
            if (DBG) Log.i(TAG, "Received wpa_supplicant event with empty event name");
            eventLogCounter++;
            return false;
        }
        /*
        * Map event name into event enum
        */
        int event;
        if (eventName.equals(CONNECTED_STR))
            event = CONNECTED;
        else if (eventName.equals(DISCONNECTED_STR))
            event = DISCONNECTED;
        else if (eventName.equals(STATE_CHANGE_STR))
            event = STATE_CHANGE;
        else if (eventName.equals(SCAN_RESULTS_STR))
            event = SCAN_RESULTS;
        else if (eventName.equals(LINK_SPEED_STR))
            event = LINK_SPEED;
        else if (eventName.equals(TERMINATING_STR))
            event = TERMINATING;
        else if (eventName.equals(DRIVER_STATE_STR))
            event = DRIVER_STATE;
        else if (eventName.equals(EAP_FAILURE_STR))
            event = EAP_FAILURE;
        else if (eventName.equals(ASSOC_REJECT_STR))
            event = ASSOC_REJECT;
        else if (eventName.equals(TEMP_DISABLED_STR)) {
            event = SSID_TEMP_DISABLE;
        } else if (eventName.equals(REENABLED_STR)) {
            event = SSID_REENABLE;
        } else if (eventName.equals(BSS_ADDED_STR)) {
            event = BSS_ADDED;
        } else if (eventName.equals(BSS_REMOVED_STR)) {
            event = BSS_REMOVED;
        }
        else
            event = UNKNOWN;

        String eventData = eventStr;
        if (event == DRIVER_STATE || event == LINK_SPEED)
            eventData = eventData.split(" ")[1];
        else if (event == STATE_CHANGE || event == EAP_FAILURE) {
            int ind = eventStr.indexOf(" ");
            if (ind != -1) {
                eventData = eventStr.substring(ind + 1);
            }
        } else {
            int ind = eventStr.indexOf(" - ");
            if (ind != -1) {
                eventData = eventStr.substring(ind + 3);
            }
        }

        if ((event == SSID_TEMP_DISABLE)||(event == SSID_REENABLE)) {
            String substr = null;
            int netId = -1;
            int ind = eventStr.indexOf(" ");
            if (ind != -1) {
                substr = eventStr.substring(ind + 1);
            }
            if (substr != null) {
                String status[] = substr.split(" ");
                for (String key : status) {
                    if (key.regionMatches(0, "id=", 0, 3)) {
                        int idx = 3;
                        netId = 0;
                        while (idx < key.length()) {
                            char c = key.charAt(idx);
                            if ((c >= 0x30) && (c <= 0x39)) {
                                netId *= 10;
                                netId += c - 0x30;
                                idx++;
                            } else {
                                break;
                            }
                        }
                    }
                }
            }
            mStateMachine.sendMessage((event == SSID_TEMP_DISABLE)?
                    SSID_TEMP_DISABLED:SSID_REENABLED, netId, 0, substr);
        } else if (event == STATE_CHANGE) {
            handleSupplicantStateChange(eventData);
        } else if (event == DRIVER_STATE) {
            handleDriverEvent(eventData);
        } else if (event == TERMINATING) {
            /**
             * Close the supplicant connection if we see
             * too many recv errors
             */
            if (eventData.startsWith(WPA_RECV_ERROR_STR)) {
                if (++sRecvErrors > MAX_RECV_ERRORS) {
                    if (DBG) {
                        Log.d(TAG, "too many recv errors, closing connection");
                    }
                } else {
                    eventLogCounter++;
                    return false;
                }
            }

            // Notify and exit
            mStateMachine.sendMessage(SUP_DISCONNECTION_EVENT, eventLogCounter);
            return true;
        } else if (event == EAP_FAILURE) {
            if (eventData.startsWith(EAP_AUTH_FAILURE_STR)) {
                logDbg("WifiMonitor send auth failure (EAP_AUTH_FAILURE) ");
                mStateMachine.sendMessage(AUTHENTICATION_FAILURE_EVENT, eventLogCounter);
            }
        } else if (event == ASSOC_REJECT) {
            Matcher match = mAssocRejectEventPattern.matcher(eventData);
            String BSSID = "";
            int status = -1;
            if (!match.find()) {
                if (DBG) Log.d(TAG, "Assoc Reject: Could not parse assoc reject string");
            } else {
                BSSID = match.group(1);
                try {
                    status = Integer.parseInt(match.group(2));
                } catch (NumberFormatException e) {
                    status = -1;
                }
            }
            mStateMachine.sendMessage(ASSOCIATION_REJECTION_EVENT, eventLogCounter, status, BSSID);
        } else if (event == BSS_ADDED && !VDBG) {
            // Ignore that event - it is not handled, and dont log it as it is too verbose
        } else if (event == BSS_REMOVED && !VDBG) {
            // Ignore that event - it is not handled, and dont log it as it is too verbose
        }  else {
                handleEvent(event, eventData);
        }
        sRecvErrors = 0;
        eventLogCounter++;
        return false;
    }

    private void handleDriverEvent(String state) {
        if (state == null) {
            return;
        }
        if (state.equals("HANGED")) {
            mStateMachine.sendMessage(DRIVER_HUNG_EVENT);
        }
    }

    /**
     * Handle all supplicant events except STATE-CHANGE
     * @param event the event type
     * @param remainder the rest of the string following the
     * event name and &quot;&#8195;&#8212;&#8195;&quot;
     */
    void handleEvent(int event, String remainder) {
        if (DBG) {
            logDbg("handleEvent " + Integer.toString(event) + "  " + remainder);
        }
        switch (event) {
            case DISCONNECTED:
                handleNetworkStateChange(NetworkInfo.DetailedState.DISCONNECTED, remainder);
                break;

            case CONNECTED:
                handleNetworkStateChange(NetworkInfo.DetailedState.CONNECTED, remainder);
                break;

            case SCAN_RESULTS:
                mStateMachine.sendMessage(SCAN_RESULTS_EVENT);
                break;

            case UNKNOWN:
                if (DBG) {
                    logDbg("handleEvent unknown: " + Integer.toString(event) + "  " + remainder);
                }
                break;
            default:
                break;
        }
    }

    private void handleTargetBSSIDEvent(String eventStr) {
        String BSSID = null;
        Matcher match = mTargetBSSIDPattern.matcher(eventStr);
        if (match.find()) {
            BSSID = match.group(1);
        } else {
            Log.d(TAG, "didn't find BSSID " + eventStr);
        }
        mStateMachine.sendMessage(WifiStateMachine.CMD_TARGET_BSSID, eventLogCounter, 0, BSSID);
    }

    private void handleAssociatedBSSIDEvent(String eventStr) {
        String BSSID = null;
        Matcher match = mAssociatedPattern.matcher(eventStr);
        if (match.find()) {
            BSSID = match.group(1);
        } else {
            Log.d(TAG, "handleAssociatedBSSIDEvent: didn't find BSSID " + eventStr);
        }
        mStateMachine.sendMessage(WifiStateMachine.CMD_ASSOCIATED_BSSID, eventLogCounter, 0, BSSID);
    }


    private void handleWpsFailEvent(String dataString) {
        final Pattern p = Pattern.compile(WPS_FAIL_PATTERN);
        Matcher match = p.matcher(dataString);
        int reason = 0;
        if (match.find()) {
            String cfgErrStr = match.group(1);
            String reasonStr = match.group(2);

            if (reasonStr != null) {
                int reasonInt = Integer.parseInt(reasonStr);
                switch(reasonInt) {
                    case REASON_TKIP_ONLY_PROHIBITED:
                        mStateMachine.sendMessage(mStateMachine.obtainMessage(WPS_FAIL_EVENT,
                                WifiManager.WPS_TKIP_ONLY_PROHIBITED, 0));
                        return;
                    case REASON_WEP_PROHIBITED:
                        mStateMachine.sendMessage(mStateMachine.obtainMessage(WPS_FAIL_EVENT,
                                WifiManager.WPS_WEP_PROHIBITED, 0));
                        return;
                    default:
                        reason = reasonInt;
                        break;
                }
            }
            if (cfgErrStr != null) {
                int cfgErrInt = Integer.parseInt(cfgErrStr);
                switch(cfgErrInt) {
                    case CONFIG_AUTH_FAILURE:
                        mStateMachine.sendMessage(mStateMachine.obtainMessage(WPS_FAIL_EVENT,
                                WifiManager.WPS_AUTH_FAILURE, 0));
                        return;
                    case CONFIG_MULTIPLE_PBC_DETECTED:
                        mStateMachine.sendMessage(mStateMachine.obtainMessage(WPS_FAIL_EVENT,
                                WifiManager.WPS_OVERLAP_ERROR, 0));
                        return;
                    default:
                        if (reason == 0) reason = cfgErrInt;
                        break;
                }
            }
        }
        //For all other errors, return a generic internal error
        mStateMachine.sendMessage(mStateMachine.obtainMessage(WPS_FAIL_EVENT,
                WifiManager.ERROR, reason));
    }

    /* <event> status=<err> and the special case of <event> reason=FREQ_CONFLICT */
    private P2pStatus p2pError(String dataString) {
        P2pStatus err = P2pStatus.UNKNOWN;
        String[] tokens = dataString.split(" ");
        if (tokens.length < 2) return err;
        String[] nameValue = tokens[1].split("=");
        if (nameValue.length != 2) return err;

        /* Handle the special case of reason=FREQ+CONFLICT */
        if (nameValue[1].equals("FREQ_CONFLICT")) {
            return P2pStatus.NO_COMMON_CHANNEL;
        }
        try {
            err = P2pStatus.valueOf(Integer.parseInt(nameValue[1]));
        } catch (NumberFormatException e) {
            e.printStackTrace();
        }
        return err;
    }

    /**
     * Handle p2p events
     */
    private void handleP2pEvents(String dataString) {
        if (dataString.startsWith(P2P_DEVICE_FOUND_STR)) {
            mStateMachine.sendMessage(P2P_DEVICE_FOUND_EVENT, new WifiP2pDevice(dataString));
        } else if (dataString.startsWith(P2P_DEVICE_LOST_STR)) {
            mStateMachine.sendMessage(P2P_DEVICE_LOST_EVENT, new WifiP2pDevice(dataString));
        } else if (dataString.startsWith(P2P_FIND_STOPPED_STR)) {
            mStateMachine.sendMessage(P2P_FIND_STOPPED_EVENT);
        } else if (dataString.startsWith(P2P_GO_NEG_REQUEST_STR)) {
            mStateMachine.sendMessage(P2P_GO_NEGOTIATION_REQUEST_EVENT,
                    new WifiP2pConfig(dataString));
        } else if (dataString.startsWith(P2P_GO_NEG_SUCCESS_STR)) {
            mStateMachine.sendMessage(P2P_GO_NEGOTIATION_SUCCESS_EVENT);
        } else if (dataString.startsWith(P2P_GO_NEG_FAILURE_STR)) {
            mStateMachine.sendMessage(P2P_GO_NEGOTIATION_FAILURE_EVENT, p2pError(dataString));
        } else if (dataString.startsWith(P2P_GROUP_FORMATION_SUCCESS_STR)) {
            mStateMachine.sendMessage(P2P_GROUP_FORMATION_SUCCESS_EVENT);
        } else if (dataString.startsWith(P2P_GROUP_FORMATION_FAILURE_STR)) {
            mStateMachine.sendMessage(P2P_GROUP_FORMATION_FAILURE_EVENT, p2pError(dataString));
        } else if (dataString.startsWith(P2P_GROUP_STARTED_STR)) {
            mStateMachine.sendMessage(P2P_GROUP_STARTED_EVENT, new WifiP2pGroup(dataString));
        } else if (dataString.startsWith(P2P_GROUP_REMOVED_STR)) {
            mStateMachine.sendMessage(P2P_GROUP_REMOVED_EVENT, new WifiP2pGroup(dataString));
        } else if (dataString.startsWith(P2P_INVITATION_RECEIVED_STR)) {
            mStateMachine.sendMessage(P2P_INVITATION_RECEIVED_EVENT,
                    new WifiP2pGroup(dataString));
        } else if (dataString.startsWith(P2P_INVITATION_RESULT_STR)) {
            mStateMachine.sendMessage(P2P_INVITATION_RESULT_EVENT, p2pError(dataString));
        } else if (dataString.startsWith(P2P_PROV_DISC_PBC_REQ_STR)) {
            mStateMachine.sendMessage(P2P_PROV_DISC_PBC_REQ_EVENT,
                    new WifiP2pProvDiscEvent(dataString));
        } else if (dataString.startsWith(P2P_PROV_DISC_PBC_RSP_STR)) {
            mStateMachine.sendMessage(P2P_PROV_DISC_PBC_RSP_EVENT,
                    new WifiP2pProvDiscEvent(dataString));
        } else if (dataString.startsWith(P2P_PROV_DISC_ENTER_PIN_STR)) {
            mStateMachine.sendMessage(P2P_PROV_DISC_ENTER_PIN_EVENT,
                    new WifiP2pProvDiscEvent(dataString));
        } else if (dataString.startsWith(P2P_PROV_DISC_SHOW_PIN_STR)) {
            mStateMachine.sendMessage(P2P_PROV_DISC_SHOW_PIN_EVENT,
                    new WifiP2pProvDiscEvent(dataString));
        } else if (dataString.startsWith(P2P_PROV_DISC_FAILURE_STR)) {
            mStateMachine.sendMessage(P2P_PROV_DISC_FAILURE_EVENT);
        } else if (dataString.startsWith(P2P_SERV_DISC_RESP_STR)) {
            List<WifiP2pServiceResponse> list = WifiP2pServiceResponse.newInstance(dataString);
            if (list != null) {
                mStateMachine.sendMessage(P2P_SERV_DISC_RESP_EVENT, list);
            } else {
                Log.e(TAG, "Null service resp " + dataString);
            }
        }
    }

    /**
     * Handle hostap events
     */
    private void handleHostApEvents(String dataString) {
        String[] tokens = dataString.split(" ");
        /* AP-STA-CONNECTED 42:fc:89:a8:96:09 p2p_dev_addr=02:90:4c:a0:92:54 */
        if (tokens[0].equals(AP_STA_CONNECTED_STR)) {
            mStateMachine.sendMessage(AP_STA_CONNECTED_EVENT, new WifiP2pDevice(dataString));
            /* AP-STA-DISCONNECTED 42:fc:89:a8:96:09 p2p_dev_addr=02:90:4c:a0:92:54 */
        } else if (tokens[0].equals(AP_STA_DISCONNECTED_STR)) {
            mStateMachine.sendMessage(AP_STA_DISCONNECTED_EVENT, new WifiP2pDevice(dataString));
        }
    }

    /**
     * Handle ANQP events
     */
    private void handleGasQueryEvents(String dataString) {
        // hs20
        if (mStateMachine2 == null) return;
        if (dataString.startsWith(GAS_QUERY_START_STR)) {
            mStateMachine2.sendMessage(GAS_QUERY_START_EVENT);
        } else if (dataString.startsWith(GAS_QUERY_DONE_STR)) {
            String[] dataTokens = dataString.split(" ");
            String bssid = null;
            int success = 0;
            for (String token : dataTokens) {
                String[] nameValue = token.split("=");
                if (nameValue.length != 2) {
                    continue;
                }
                if (nameValue[0].equals("addr")) {
                    bssid = nameValue[1];
                    continue;
                }
                if (nameValue[0].equals("result"))  {
                    success = nameValue[1].equals("SUCCESS") ? 1 : 0;
                    continue;
                }
            }
            mStateMachine2.sendMessage(GAS_QUERY_DONE_EVENT, success, 0, bssid);
        } else {
            if (DBG) Log.d(TAG, "Unknown GAS query event: " + dataString);
        }
    }

    /**
     * Handle HS20 events
     */
    private void handleHs20Events(String dataString) {
        if (mStateMachine2 == null) return;
        if (dataString.startsWith(HS20_SUB_REM_STR)) {
            // format: HS20-SUBSCRIPTION-REMEDIATION osu_method, url
            String[] dataTokens = dataString.split(" ");
            int method = -1;
            String url = null;
            if (dataTokens.length >= 3) {
                method = Integer.parseInt(dataTokens[1]);
                url = dataTokens[2];
            }
            mStateMachine2.sendMessage(HS20_REMEDIATION_EVENT, method, 0, url);
        } else if (dataString.startsWith(HS20_DEAUTH_STR)) {
            // format: HS20-DEAUTH-IMMINENT-NOTICE code, delay, url
            int code = -1;
            int delay = -1;
            String url = null;
            String[] dataTokens = dataString.split(" ");
            if (dataTokens.length >= 4) {
                code = Integer.parseInt(dataTokens[1]);
                delay = Integer.parseInt(dataTokens[2]);
                url = dataTokens[3];
            }
            mStateMachine2.sendMessage(HS20_DEAUTH_EVENT, code, delay, url);
        } else {
            if (DBG) Log.d(TAG, "Unknown HS20 event: " + dataString);
        }
    }

    /**
     * Handle Supplicant Requests
     */
    private void handleRequests(String dataString) {
        String SSID = null;
        int reason = -2;
        String requestName = dataString.substring(REQUEST_PREFIX_LEN_STR);
        if (TextUtils.isEmpty(requestName)) {
            return;
        }
        if (requestName.startsWith(IDENTITY_STR)) {
            Matcher match = mRequestIdentityPattern.matcher(requestName);
            if (match.find()) {
                SSID = match.group(2);
                try {
                    reason = Integer.parseInt(match.group(1));
                } catch (NumberFormatException e) {
                    reason = -1;
                }
            } else {
                Log.e(TAG, "didn't find SSID " + requestName);
            }
            mStateMachine.sendMessage(SUP_REQUEST_IDENTITY, eventLogCounter, reason, SSID);
        } if (requestName.startsWith(SIM_STR)) {
            Matcher match = mRequestGsmAuthPattern.matcher(requestName);
            if (match.find()) {
                WifiStateMachine.SimAuthRequestData data =
                        new WifiStateMachine.SimAuthRequestData();
                data.networkId = Integer.parseInt(match.group(1));
                data.protocol = WifiEnterpriseConfig.Eap.SIM;
                data.ssid = match.group(4);
                data.challenges = match.group(2).split(":");
                mStateMachine.sendMessage(SUP_REQUEST_SIM_AUTH, data);
            } else {
                Log.e(TAG, "couldn't parse SIM auth request - " + requestName);
            }

        } else {
            if (DBG) Log.w(TAG, "couldn't identify request type - " + dataString);
        }
    }

    /**
     * Handle the supplicant STATE-CHANGE event
     * @param dataString New supplicant state string in the format:
     * id=network-id state=new-state
     */
    private void handleSupplicantStateChange(String dataString) {
        WifiSsid wifiSsid = null;
        int index = dataString.lastIndexOf("SSID=");
        if (index != -1) {
            wifiSsid = WifiSsid.createFromAsciiEncoded(
                    dataString.substring(index + 5));
        }
        String[] dataTokens = dataString.split(" ");

        String BSSID = null;
        int networkId = -1;
        int newState  = -1;
        for (String token : dataTokens) {
            String[] nameValue = token.split("=");
            if (nameValue.length != 2) {
                continue;
            }

            if (nameValue[0].equals("BSSID")) {
                BSSID = nameValue[1];
                continue;
            }

            int value;
            try {
                value = Integer.parseInt(nameValue[1]);
            } catch (NumberFormatException e) {
                continue;
            }

            if (nameValue[0].equals("id")) {
                networkId = value;
            } else if (nameValue[0].equals("state")) {
                newState = value;
            }
        }

        if (newState == -1) return;

        SupplicantState newSupplicantState = SupplicantState.INVALID;
        for (SupplicantState state : SupplicantState.values()) {
            if (state.ordinal() == newState) {
                newSupplicantState = state;
                break;
            }
        }
        if (newSupplicantState == SupplicantState.INVALID) {
            Log.w(TAG, "Invalid supplicant state: " + newState);
        }
        notifySupplicantStateChange(networkId, wifiSsid, BSSID, newSupplicantState);
    }

    private void handleNetworkStateChange(NetworkInfo.DetailedState newState, String data) {
        String BSSID = null;
        int networkId = -1;
        int reason = 0;
        int ind = -1;
        int local = 0;
        Matcher match;
        if (newState == NetworkInfo.DetailedState.CONNECTED) {
            match = mConnectedEventPattern.matcher(data);
            if (!match.find()) {
               if (DBG) Log.d(TAG, "handleNetworkStateChange: Couldnt find BSSID in event string");
            } else {
                BSSID = match.group(1);
                try {
                    networkId = Integer.parseInt(match.group(2));
                } catch (NumberFormatException e) {
                    networkId = -1;
                }
            }
            notifyNetworkStateChange(newState, BSSID, networkId, reason);
        } else if (newState == NetworkInfo.DetailedState.DISCONNECTED) {
            match = mDisconnectedEventPattern.matcher(data);
            if (!match.find()) {
               if (DBG) Log.d(TAG, "handleNetworkStateChange: Could not parse disconnect string");
            } else {
                BSSID = match.group(1);
                try {
                    reason = Integer.parseInt(match.group(2));
                } catch (NumberFormatException e) {
                    reason = -1;
                }
                try {
                    local = Integer.parseInt(match.group(3));
                } catch (NumberFormatException e) {
                    local = -1;
                }
            }
            notifyNetworkStateChange(newState, BSSID, local, reason);
        }
    }

    /**
     * Send the state machine a notification that the state of Wifi connectivity
     * has changed.
     * @param newState the new network state
     * @param BSSID when the new state is {@link NetworkInfo.DetailedState#CONNECTED},
     * this is the MAC address of the access point. Otherwise, it
     * is {@code null}.
     * @param netId the configured network on which the state change occurred
     */
    void notifyNetworkStateChange(NetworkInfo.DetailedState newState,
                                  String BSSID, int netId, int reason) {
        if (newState == NetworkInfo.DetailedState.CONNECTED) {
            Message m = mStateMachine.obtainMessage(NETWORK_CONNECTION_EVENT,
                    netId, reason, BSSID);
            mStateMachine.sendMessage(m);
        } else {

            Message m = mStateMachine.obtainMessage(NETWORK_DISCONNECTION_EVENT,
                    netId, reason, BSSID);
            if (DBG) logDbg("WifiMonitor notify network disconnect: "
                    + BSSID
                    + " reason=" + Integer.toString(reason));
            mStateMachine.sendMessage(m);
        }
    }

    /**
     * Send the state machine a notification that the state of the supplicant
     * has changed.
     * @param networkId the configured network on which the state change occurred
     * @param wifiSsid network name
     * @param BSSID network address
     * @param newState the new {@code SupplicantState}
     */
    void notifySupplicantStateChange(int networkId, WifiSsid wifiSsid, String BSSID,
            SupplicantState newState) {
        mStateMachine.sendMessage(mStateMachine.obtainMessage(SUPPLICANT_STATE_CHANGE_EVENT,
                eventLogCounter, 0,
                new StateChangeResult(networkId, wifiSsid, BSSID, newState)));
    }
}
