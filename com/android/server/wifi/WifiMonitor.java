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

import android.net.wifi.SupplicantState;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.os.Handler;
import android.os.Message;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Protocol;
import com.android.internal.util.StateMachine;
import com.android.server.wifi.hotspot2.AnqpEvent;
import com.android.server.wifi.hotspot2.IconEvent;
import com.android.server.wifi.hotspot2.WnmData;
import com.android.server.wifi.util.TelephonyUtil.SimAuthRequestData;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Listens for events from the wpa_supplicant server, and passes them on
 * to the {@link StateMachine} for handling.
 *
 * @hide
 */
public class WifiMonitor {
    private static final String TAG = "WifiMonitor";

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

    /* Request Identity */
    public static final int SUP_REQUEST_IDENTITY                 = BASE + 15;

    /* Request SIM Auth */
    public static final int SUP_REQUEST_SIM_AUTH                 = BASE + 16;

    public static final int SCAN_FAILED_EVENT                    = BASE + 17;
    /* Pno scan results are available */
    public static final int PNO_SCAN_RESULTS_EVENT               = BASE + 18;


    /* Indicates assoc reject event */
    public static final int ASSOCIATION_REJECTION_EVENT          = BASE + 43;
    public static final int ANQP_DONE_EVENT                      = BASE + 44;

    /* hotspot 2.0 ANQP events */
    public static final int GAS_QUERY_START_EVENT                = BASE + 51;
    public static final int GAS_QUERY_DONE_EVENT                 = BASE + 52;
    public static final int RX_HS20_ANQP_ICON_EVENT              = BASE + 53;

    /* hotspot 2.0 events */
    public static final int HS20_REMEDIATION_EVENT               = BASE + 61;

    /* WPS config errrors */
    private static final int CONFIG_MULTIPLE_PBC_DETECTED = 12;
    private static final int CONFIG_AUTH_FAILURE = 18;

    /* WPS error indications */
    private static final int REASON_TKIP_ONLY_PROHIBITED = 1;
    private static final int REASON_WEP_PROHIBITED = 2;

    private final WifiInjector mWifiInjector;
    private boolean mVerboseLoggingEnabled = false;
    private boolean mConnected = false;

    public WifiMonitor(WifiInjector wifiInjector) {
        mWifiInjector = wifiInjector;
    }

    void enableVerboseLogging(int verbose) {
        if (verbose > 0) {
            mVerboseLoggingEnabled = true;
        } else {
            mVerboseLoggingEnabled = false;
        }
    }

    // TODO(b/27569474) remove support for multiple handlers for the same event
    private final Map<String, SparseArray<Set<Handler>>> mHandlerMap = new HashMap<>();
    public synchronized void registerHandler(String iface, int what, Handler handler) {
        SparseArray<Set<Handler>> ifaceHandlers = mHandlerMap.get(iface);
        if (ifaceHandlers == null) {
            ifaceHandlers = new SparseArray<>();
            mHandlerMap.put(iface, ifaceHandlers);
        }
        Set<Handler> ifaceWhatHandlers = ifaceHandlers.get(what);
        if (ifaceWhatHandlers == null) {
            ifaceWhatHandlers = new ArraySet<>();
            ifaceHandlers.put(what, ifaceWhatHandlers);
        }
        ifaceWhatHandlers.add(handler);
    }

    /**
     * Deregister the given |handler|
     * @param iface
     * @param what
     * @param handler
     */
    public synchronized void deregisterHandler(String iface, int what, Handler handler) {
        SparseArray<Set<Handler>> ifaceHandlers = mHandlerMap.get(iface);
        if (ifaceHandlers == null) {
            return;
        }
        Set<Handler> ifaceWhatHandlers = ifaceHandlers.get(what);
        if (ifaceWhatHandlers == null) {
            return;
        }
        ifaceWhatHandlers.remove(handler);
    }

    private final Map<String, Boolean> mMonitoringMap = new HashMap<>();
    private boolean isMonitoring(String iface) {
        Boolean val = mMonitoringMap.get(iface);
        if (val == null) {
            return false;
        } else {
            return val.booleanValue();
        }
    }

    /**
     * Enable/Disable monitoring for the provided iface.
     *
     * @param iface Name of the iface.
     * @param enabled true to enable, false to disable.
     */
    @VisibleForTesting
    public void setMonitoring(String iface, boolean enabled) {
        mMonitoringMap.put(iface, enabled);
    }

    private void setMonitoringNone() {
        for (String iface : mMonitoringMap.keySet()) {
            setMonitoring(iface, false);
        }
    }

    /**
     * Start Monitoring for wpa_supplicant events.
     *
     * @param iface Name of iface.
     */
    public synchronized void startMonitoring(String iface) {
        if (mVerboseLoggingEnabled) Log.d(TAG, "startMonitoring(" + iface + ")");
        setMonitoring(iface, true);
        broadcastSupplicantConnectionEvent(iface);
    }

    /**
     * Stop Monitoring for wpa_supplicant events.
     *
     * @param iface Name of iface.
     */
    public synchronized void stopMonitoring(String iface) {
        if (mVerboseLoggingEnabled) Log.d(TAG, "stopMonitoring(" + iface + ")");
        setMonitoring(iface, true);
        broadcastSupplicantDisconnectionEvent(iface);
        setMonitoring(iface, false);
    }

    /**
     * Stop Monitoring for wpa_supplicant events.
     *
     * TODO: Add unit tests for these once we remove the legacy code.
     */
    public synchronized void stopAllMonitoring() {
        mConnected = false;
        setMonitoringNone();
    }


    /**
     * Similar functions to Handler#sendMessage that send the message to the registered handler
     * for the given interface and message what.
     * All of these should be called with the WifiMonitor class lock
     */
    private void sendMessage(String iface, int what) {
        sendMessage(iface, Message.obtain(null, what));
    }

    private void sendMessage(String iface, int what, Object obj) {
        sendMessage(iface, Message.obtain(null, what, obj));
    }

    private void sendMessage(String iface, int what, int arg1) {
        sendMessage(iface, Message.obtain(null, what, arg1, 0));
    }

    private void sendMessage(String iface, int what, int arg1, int arg2) {
        sendMessage(iface, Message.obtain(null, what, arg1, arg2));
    }

    private void sendMessage(String iface, int what, int arg1, int arg2, Object obj) {
        sendMessage(iface, Message.obtain(null, what, arg1, arg2, obj));
    }

    private void sendMessage(String iface, Message message) {
        SparseArray<Set<Handler>> ifaceHandlers = mHandlerMap.get(iface);
        if (iface != null && ifaceHandlers != null) {
            if (isMonitoring(iface)) {
                Set<Handler> ifaceWhatHandlers = ifaceHandlers.get(message.what);
                if (ifaceWhatHandlers != null) {
                    for (Handler handler : ifaceWhatHandlers) {
                        if (handler != null) {
                            sendMessage(handler, Message.obtain(message));
                        }
                    }
                }
            } else {
                if (mVerboseLoggingEnabled) {
                    Log.d(TAG, "Dropping event because (" + iface + ") is stopped");
                }
            }
        } else {
            if (mVerboseLoggingEnabled) {
                Log.d(TAG, "Sending to all monitors because there's no matching iface");
            }
            for (Map.Entry<String, SparseArray<Set<Handler>>> entry : mHandlerMap.entrySet()) {
                if (isMonitoring(entry.getKey())) {
                    Set<Handler> ifaceWhatHandlers = entry.getValue().get(message.what);
                    for (Handler handler : ifaceWhatHandlers) {
                        if (handler != null) {
                            sendMessage(handler, Message.obtain(message));
                        }
                    }
                }
            }
        }

        message.recycle();
    }

    private void sendMessage(Handler handler, Message message) {
        message.setTarget(handler);
        message.sendToTarget();
    }

    /**
     * Broadcast the WPS fail event to all the handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param cfgError Configuration error code.
     * @param vendorErrorCode Vendor specific error indication code.
     */
    public void broadcastWpsFailEvent(String iface, int cfgError, int vendorErrorCode) {
        int reason = 0;
        switch(vendorErrorCode) {
            case REASON_TKIP_ONLY_PROHIBITED:
                sendMessage(iface, WPS_FAIL_EVENT, WifiManager.WPS_TKIP_ONLY_PROHIBITED);
                return;
            case REASON_WEP_PROHIBITED:
                sendMessage(iface, WPS_FAIL_EVENT, WifiManager.WPS_WEP_PROHIBITED);
                return;
            default:
                reason = vendorErrorCode;
                break;
        }
        switch(cfgError) {
            case CONFIG_AUTH_FAILURE:
                sendMessage(iface, WPS_FAIL_EVENT, WifiManager.WPS_AUTH_FAILURE);
                return;
            case CONFIG_MULTIPLE_PBC_DETECTED:
                sendMessage(iface, WPS_FAIL_EVENT, WifiManager.WPS_OVERLAP_ERROR);
                return;
            default:
                if (reason == 0) {
                    reason = cfgError;
                }
                break;
        }
        //For all other errors, return a generic internal error
        sendMessage(iface, WPS_FAIL_EVENT, WifiManager.ERROR, reason);
    }

   /**
    * Broadcast the WPS succes event to all the handlers registered for this event.
    *
    * @param iface Name of iface on which this occurred.
    */
    public void broadcastWpsSuccessEvent(String iface) {
        sendMessage(iface, WPS_SUCCESS_EVENT);
    }

    /**
     * Broadcast the WPS overlap event to all the handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     */
    public void broadcastWpsOverlapEvent(String iface) {
        sendMessage(iface, WPS_OVERLAP_EVENT);
    }

    /**
     * Broadcast the WPS timeout event to all the handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     */
    public void broadcastWpsTimeoutEvent(String iface) {
        sendMessage(iface, WPS_TIMEOUT_EVENT);
    }

    /**
     * Broadcast the ANQP done event to all the handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param anqpEvent ANQP result retrieved.
     */
    public void broadcastAnqpDoneEvent(String iface, AnqpEvent anqpEvent) {
        sendMessage(iface, ANQP_DONE_EVENT, anqpEvent);
    }

    /**
     * Broadcast the Icon done event to all the handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param iconEvent Instance of IconEvent containing the icon data retrieved.
     */
    public void broadcastIconDoneEvent(String iface, IconEvent iconEvent) {
        sendMessage(iface, RX_HS20_ANQP_ICON_EVENT, iconEvent);
    }

    /**
     * Broadcast the WNM event to all the handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param wnmData Instance of WnmData containing the event data.
     */
    public void broadcastWnmEvent(String iface, WnmData wnmData) {
        sendMessage(iface, HS20_REMEDIATION_EVENT, wnmData);
    }

    /**
     * Broadcast the Network identity request event to all the handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param networkId ID of the network in wpa_supplicant.
     * @param ssid SSID of the network.
     */
    public void broadcastNetworkIdentityRequestEvent(String iface, int networkId, String ssid) {
        sendMessage(iface, SUP_REQUEST_IDENTITY, 0, networkId, ssid);
    }

    /**
     * Broadcast the Network Gsm Sim auth request event to all the handlers registered for this
     * event.
     *
     * @param iface Name of iface on which this occurred.
     * @param networkId ID of the network in wpa_supplicant.
     * @param ssid SSID of the network.
     * @param data Accompanying event data.
     */
    public void broadcastNetworkGsmAuthRequestEvent(String iface, int networkId, String ssid,
                                                    String[] data) {
        sendMessage(iface, SUP_REQUEST_SIM_AUTH,
                new SimAuthRequestData(networkId, WifiEnterpriseConfig.Eap.SIM, ssid, data));
    }

    /**
     * Broadcast the Network Umts Sim auth request event to all the handlers registered for this
     * event.
     *
     * @param iface Name of iface on which this occurred.
     * @param networkId ID of the network in wpa_supplicant.
     * @param ssid SSID of the network.
     * @param data Accompanying event data.
     */
    public void broadcastNetworkUmtsAuthRequestEvent(String iface, int networkId, String ssid,
                                                     String[] data) {
        sendMessage(iface, SUP_REQUEST_SIM_AUTH,
                new SimAuthRequestData(networkId, WifiEnterpriseConfig.Eap.AKA, ssid, data));
    }

    /**
     * Broadcast scan result event to all the handlers registered for this event.
     * @param iface Name of iface on which this occurred.
     */
    public void broadcastScanResultEvent(String iface) {
        sendMessage(iface, SCAN_RESULTS_EVENT);
    }

    /**
     * Broadcast pno scan result event to all the handlers registered for this event.
     * @param iface Name of iface on which this occurred.
     */
    public void broadcastPnoScanResultEvent(String iface) {
        sendMessage(iface, PNO_SCAN_RESULTS_EVENT);
    }

    /**
     * Broadcast scan failed event to all the handlers registered for this event.
     * @param iface Name of iface on which this occurred.
     */
    public void broadcastScanFailedEvent(String iface) {
        sendMessage(iface, SCAN_FAILED_EVENT);
    }

    /**
     * Broadcast the authentication failure event to all the handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param reason Reason for authentication failure. This has to be one of the
     *               {@link android.net.wifi.WifiManager#ERROR_AUTH_FAILURE_NONE},
     *               {@link android.net.wifi.WifiManager#ERROR_AUTH_FAILURE_TIMEOUT},
     *               {@link android.net.wifi.WifiManager#ERROR_AUTH_FAILURE_WRONG_PSWD},
     *               {@link android.net.wifi.WifiManager#ERROR_AUTH_FAILURE_EAP_FAILURE}
     * @param errorCode Error code associated with the authentication failure event.
     *               A value of -1 is used when no error code is reported.
     */
    public void broadcastAuthenticationFailureEvent(String iface, int reason, int errorCode) {
        sendMessage(iface, AUTHENTICATION_FAILURE_EVENT, reason, errorCode);
    }

    /**
     * Broadcast the association rejection event to all the handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param status Status code for association rejection.
     * @param timedOut Indicates if the association timed out.
     * @param bssid BSSID of the access point from which we received the reject.
     */
    public void broadcastAssociationRejectionEvent(String iface, int status, boolean timedOut,
                                                   String bssid) {
        sendMessage(iface, ASSOCIATION_REJECTION_EVENT, timedOut ? 1 : 0, status, bssid);
    }

    /**
     * Broadcast the association success event to all the handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param bssid BSSID of the access point.
     */
    public void broadcastAssociatedBssidEvent(String iface, String bssid) {
        sendMessage(iface, WifiStateMachine.CMD_ASSOCIATED_BSSID, 0, 0, bssid);
    }

    /**
     * Broadcast the start of association event to all the handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param bssid BSSID of the access point.
     */
    public void broadcastTargetBssidEvent(String iface, String bssid) {
        sendMessage(iface, WifiStateMachine.CMD_TARGET_BSSID, 0, 0, bssid);
    }

    /**
     * Broadcast the network connection event to all the handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param networkId ID of the network in wpa_supplicant.
     * @param bssid BSSID of the access point.
     */
    public void broadcastNetworkConnectionEvent(String iface, int networkId, String bssid) {
        sendMessage(iface, NETWORK_CONNECTION_EVENT, networkId, 0, bssid);
    }

    /**
     * Broadcast the network disconnection event to all the handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param local Whether the disconnect was locally triggered.
     * @param reason Disconnect reason code.
     * @param bssid BSSID of the access point.
     */
    public void broadcastNetworkDisconnectionEvent(String iface, int local, int reason,
                                                   String bssid) {
        sendMessage(iface, NETWORK_DISCONNECTION_EVENT, local, reason, bssid);
    }

    /**
     * Broadcast the supplicant state change event to all the handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param networkId ID of the network in wpa_supplicant.
     * @param bssid BSSID of the access point.
     * @param newSupplicantState New supplicant state.
     */
    public void broadcastSupplicantStateChangeEvent(String iface, int networkId, WifiSsid wifiSsid,
                                                    String bssid,
                                                    SupplicantState newSupplicantState) {
        sendMessage(iface, SUPPLICANT_STATE_CHANGE_EVENT, 0, 0,
                new StateChangeResult(networkId, wifiSsid, bssid, newSupplicantState));
    }

    /**
     * Broadcast the connection to wpa_supplicant event to all the handlers registered for
     * this event.
     *
     * @param iface Name of iface on which this occurred.
     */
    public void broadcastSupplicantConnectionEvent(String iface) {
        sendMessage(iface, SUP_CONNECTION_EVENT);
    }

    /**
     * Broadcast the loss of connection to wpa_supplicant event to all the handlers registered for
     * this event.
     *
     * @param iface Name of iface on which this occurred.
     */
    public void broadcastSupplicantDisconnectionEvent(String iface) {
        sendMessage(iface, SUP_DISCONNECTION_EVENT);
    }
}
