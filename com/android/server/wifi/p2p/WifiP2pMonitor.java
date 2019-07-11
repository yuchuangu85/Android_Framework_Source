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

import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pProvDiscEvent;
import android.net.wifi.p2p.nsd.WifiP2pServiceResponse;
import android.os.Handler;
import android.os.Message;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Protocol;
import com.android.server.wifi.WifiInjector;
import com.android.server.wifi.p2p.WifiP2pServiceImpl.P2pStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Listens for events from the wpa_supplicant, and passes them on
 * to the {@link WifiP2pServiceImpl} for handling.
 *
 * @hide
 */
public class WifiP2pMonitor {
    private static final String TAG = "WifiP2pMonitor";

    /* Supplicant events reported to a state machine */
    private static final int BASE = Protocol.BASE_WIFI_MONITOR;

    /* Connection to supplicant established */
    public static final int SUP_CONNECTION_EVENT                 = BASE + 1;
    /* Connection to supplicant lost */
    public static final int SUP_DISCONNECTION_EVENT              = BASE + 2;

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


    private final WifiInjector mWifiInjector;
    private boolean mVerboseLoggingEnabled = false;
    private boolean mConnected = false;

    public WifiP2pMonitor(WifiInjector wifiInjector) {
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

    /**
     * Registers a callback handler for the provided event.
     */
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
     * TODO: Add unit tests for these once we remove the legacy code.
     */
    public synchronized void startMonitoring(String iface) {
        setMonitoring(iface, true);
        broadcastSupplicantConnectionEvent(iface);
    }

    /**
     * Stop Monitoring for wpa_supplicant events.
     *
     * @param iface Name of iface.
     * TODO: Add unit tests for these once we remove the legacy code.
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

    /**
     * Broadcast new p2p device discovered event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param device Device that has been discovered during recent scan.
     */
    public void broadcastP2pDeviceFound(String iface, WifiP2pDevice device) {
        if (device != null) {
            sendMessage(iface, P2P_DEVICE_FOUND_EVENT, device);
        }
    }

    /**
     * Broadcast p2p device lost event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param device Device that has been lost in recent scan.
     */
    public void broadcastP2pDeviceLost(String iface, WifiP2pDevice device) {
        if (device != null) {
            sendMessage(iface, P2P_DEVICE_LOST_EVENT, device);
        }
    }

    /**
     * Broadcast scan termination event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     */
    public void broadcastP2pFindStopped(String iface) {
        sendMessage(iface, P2P_FIND_STOPPED_EVENT);
    }

    /**
     * Broadcast group owner negotiation request event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param config P2p configuration.
     */
    public void broadcastP2pGoNegotiationRequest(String iface, WifiP2pConfig config) {
        if (config != null) {
            sendMessage(iface, P2P_GO_NEGOTIATION_REQUEST_EVENT, config);
        }
    }

    /**
     * Broadcast group owner negotiation success event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     */
    public void broadcastP2pGoNegotiationSuccess(String iface) {
        sendMessage(iface, P2P_GO_NEGOTIATION_SUCCESS_EVENT);
    }

    /**
     * Broadcast group owner negotiation failure event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param reason Failure reason.
     */
    public void broadcastP2pGoNegotiationFailure(String iface, P2pStatus reason) {
        sendMessage(iface, P2P_GO_NEGOTIATION_FAILURE_EVENT, reason);
    }

    /**
     * Broadcast group formation success event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     */
    public void broadcastP2pGroupFormationSuccess(String iface) {
        sendMessage(iface, P2P_GROUP_FORMATION_SUCCESS_EVENT);
    }

    /**
     * Broadcast group formation failure event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param reason Failure reason.
     */
    public void broadcastP2pGroupFormationFailure(String iface, String reason) {
        P2pStatus err = P2pStatus.UNKNOWN;
        if (reason.equals("FREQ_CONFLICT")) {
            err = P2pStatus.NO_COMMON_CHANNEL;
        }
        sendMessage(iface, P2P_GROUP_FORMATION_FAILURE_EVENT, err);
    }

    /**
     * Broadcast group started event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param group Started group.
     */
    public void broadcastP2pGroupStarted(String iface, WifiP2pGroup group) {
        if (group != null) {
            sendMessage(iface, P2P_GROUP_STARTED_EVENT, group);
        }
    }

    /**
     * Broadcast group removed event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param group Removed group.
     */
    public void broadcastP2pGroupRemoved(String iface, WifiP2pGroup group) {
        if (group != null) {
            sendMessage(iface, P2P_GROUP_REMOVED_EVENT, group);
        }
    }

    /**
     * Broadcast invitation received event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param group Group to which invitation has been received.
     */
    public void broadcastP2pInvitationReceived(String iface, WifiP2pGroup group) {
        if (group != null) {
            sendMessage(iface, P2P_INVITATION_RECEIVED_EVENT, group);
        }
    }

    /**
     * Broadcast invitation result event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param result Result of invitation.
     */
    public void broadcastP2pInvitationResult(String iface, P2pStatus result) {
        sendMessage(iface, P2P_INVITATION_RESULT_EVENT, result);
    }

    /**
     * Broadcast PB discovery request event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param event Provision discovery request event.
     */
    public void broadcastP2pProvisionDiscoveryPbcRequest(String iface, WifiP2pProvDiscEvent event) {
        if (event != null) {
            sendMessage(iface, P2P_PROV_DISC_PBC_REQ_EVENT, event);
        }
    }

    /**
     * Broadcast PB discovery response event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param event Provision discovery response event.
     */
    public void broadcastP2pProvisionDiscoveryPbcResponse(
            String iface, WifiP2pProvDiscEvent event) {
        if (event != null) {
            sendMessage(iface, P2P_PROV_DISC_PBC_RSP_EVENT, event);
        }
    }

    /**
     * Broadcast PIN discovery request event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param event Provision discovery request event.
     */
    public void broadcastP2pProvisionDiscoveryEnterPin(String iface, WifiP2pProvDiscEvent event) {
        if (event != null) {
            sendMessage(iface, P2P_PROV_DISC_ENTER_PIN_EVENT, event);
        }
    }

    /**
     * Broadcast PIN discovery response event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param event Provision discovery response event.
     */
    public void broadcastP2pProvisionDiscoveryShowPin(String iface, WifiP2pProvDiscEvent event) {
        if (event != null) {
            sendMessage(iface, P2P_PROV_DISC_SHOW_PIN_EVENT, event);
        }
    }

    /**
     * Broadcast P2P discovery failure event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     */
    public void broadcastP2pProvisionDiscoveryFailure(String iface) {
        sendMessage(iface, P2P_PROV_DISC_FAILURE_EVENT);
    }

    /**
     * Broadcast service discovery response event to all handlers registered for this event.
     *
     * @param iface Name of iface on which this occurred.
     * @param services List of discovered services.
     */
    public void broadcastP2pServiceDiscoveryResponse(
            String iface, List<WifiP2pServiceResponse> services) {
        sendMessage(iface, P2P_SERV_DISC_RESP_EVENT, services);
    }

    /**
     * Broadcast AP STA connection event.
     *
     * @param iface Name of iface on which this occurred.
     */
    public void broadcastP2pApStaConnected(String iface, WifiP2pDevice device) {
        sendMessage(iface, AP_STA_CONNECTED_EVENT, device);
    }

    /**
     * Broadcast AP STA disconnection event.
     *
     * @param iface Name of iface on which this occurred.
     */
    public void broadcastP2pApStaDisconnected(String iface, WifiP2pDevice device) {
        sendMessage(iface, AP_STA_DISCONNECTED_EVENT, device);
    }
}
