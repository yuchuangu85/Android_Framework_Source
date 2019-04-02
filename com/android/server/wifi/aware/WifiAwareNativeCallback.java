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

package com.android.server.wifi.aware;

import android.hardware.wifi.V1_0.IWifiNanIfaceEventCallback;
import android.hardware.wifi.V1_0.NanCapabilities;
import android.hardware.wifi.V1_0.NanClusterEventInd;
import android.hardware.wifi.V1_0.NanClusterEventType;
import android.hardware.wifi.V1_0.NanDataPathConfirmInd;
import android.hardware.wifi.V1_0.NanDataPathRequestInd;
import android.hardware.wifi.V1_0.NanFollowupReceivedInd;
import android.hardware.wifi.V1_0.NanMatchInd;
import android.hardware.wifi.V1_0.NanStatusType;
import android.hardware.wifi.V1_0.WifiNanStatus;
import android.os.ShellCommand;
import android.util.Log;
import android.util.SparseIntArray;

import libcore.util.HexEncoding;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Manages the callbacks from Wi-Fi Aware HIDL (HAL).
 */
public class WifiAwareNativeCallback extends IWifiNanIfaceEventCallback.Stub implements
        WifiAwareShellCommand.DelegatedShellCommand {
    private static final String TAG = "WifiAwareNativeCallback";
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    private final WifiAwareStateManager mWifiAwareStateManager;

    public WifiAwareNativeCallback(WifiAwareStateManager wifiAwareStateManager) {
        mWifiAwareStateManager = wifiAwareStateManager;
    }

    /*
     * Counts of callbacks from HAL. Retrievable through shell command.
     */
    private static final int CB_EV_CLUSTER = 0;
    private static final int CB_EV_DISABLED = 1;
    private static final int CB_EV_PUBLISH_TERMINATED = 2;
    private static final int CB_EV_SUBSCRIBE_TERMINATED = 3;
    private static final int CB_EV_MATCH = 4;
    private static final int CB_EV_MATCH_EXPIRED = 5;
    private static final int CB_EV_FOLLOWUP_RECEIVED = 6;
    private static final int CB_EV_TRANSMIT_FOLLOWUP = 7;
    private static final int CB_EV_DATA_PATH_REQUEST = 8;
    private static final int CB_EV_DATA_PATH_CONFIRM = 9;
    private static final int CB_EV_DATA_PATH_TERMINATED = 10;

    private SparseIntArray mCallbackCounter = new SparseIntArray();

    private void incrementCbCount(int callbackId) {
        mCallbackCounter.put(callbackId, mCallbackCounter.get(callbackId) + 1);
    }

    /**
     * Interpreter of adb shell command 'adb shell wifiaware native_cb ...'.
     *
     * @return -1 if parameter not recognized or invalid value, 0 otherwise.
     */
    @Override
    public int onCommand(ShellCommand parentShell) {
        final PrintWriter pwe = parentShell.getErrPrintWriter();
        final PrintWriter pwo = parentShell.getOutPrintWriter();

        String subCmd = parentShell.getNextArgRequired();
        if (VDBG) Log.v(TAG, "onCommand: subCmd='" + subCmd + "'");
        switch (subCmd) {
            case "get_cb_count": {
                String option = parentShell.getNextOption();
                Log.v(TAG, "option='" + option + "'");
                boolean reset = false;
                if (option != null) {
                    if ("--reset".equals(option)) {
                        reset = true;
                    } else {
                        pwe.println("Unknown option to 'get_cb_count'");
                        return -1;
                    }
                }

                JSONObject j = new JSONObject();
                try {
                    for (int i = 0; i < mCallbackCounter.size(); ++i) {
                        j.put(Integer.toString(mCallbackCounter.keyAt(i)),
                                mCallbackCounter.valueAt(i));
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "onCommand: get_cb_count e=" + e);
                }
                pwo.println(j.toString());
                if (reset) {
                    mCallbackCounter.clear();
                }
                return 0;
            }
            default:
                pwe.println("Unknown 'wifiaware native_cb <cmd>'");
        }

        return -1;
    }

    @Override
    public void onReset() {
        // NOP (onReset is intended for configuration reset - not data reset)
    }

    @Override
    public void onHelp(String command, ShellCommand parentShell) {
        final PrintWriter pw = parentShell.getOutPrintWriter();

        pw.println("  " + command);
        pw.println("    get_cb_count [--reset]: gets the number of callbacks (and optionally reset "
                + "count)");
    }

    @Override
    public void notifyCapabilitiesResponse(short id, WifiNanStatus status,
            NanCapabilities capabilities) {
        if (VDBG) {
            Log.v(TAG, "notifyCapabilitiesResponse: id=" + id + ", status=" + statusString(status)
                    + ", capabilities=" + capabilities);
        }

        if (status.status == NanStatusType.SUCCESS) {
            Capabilities frameworkCapabilities = new Capabilities();
            frameworkCapabilities.maxConcurrentAwareClusters = capabilities.maxConcurrentClusters;
            frameworkCapabilities.maxPublishes = capabilities.maxPublishes;
            frameworkCapabilities.maxSubscribes = capabilities.maxSubscribes;
            frameworkCapabilities.maxServiceNameLen = capabilities.maxServiceNameLen;
            frameworkCapabilities.maxMatchFilterLen = capabilities.maxMatchFilterLen;
            frameworkCapabilities.maxTotalMatchFilterLen = capabilities.maxTotalMatchFilterLen;
            frameworkCapabilities.maxServiceSpecificInfoLen =
                    capabilities.maxServiceSpecificInfoLen;
            frameworkCapabilities.maxExtendedServiceSpecificInfoLen =
                    capabilities.maxExtendedServiceSpecificInfoLen;
            frameworkCapabilities.maxNdiInterfaces = capabilities.maxNdiInterfaces;
            frameworkCapabilities.maxNdpSessions = capabilities.maxNdpSessions;
            frameworkCapabilities.maxAppInfoLen = capabilities.maxAppInfoLen;
            frameworkCapabilities.maxQueuedTransmitMessages =
                    capabilities.maxQueuedTransmitFollowupMsgs;
            frameworkCapabilities.maxSubscribeInterfaceAddresses =
                    capabilities.maxSubscribeInterfaceAddresses;
            frameworkCapabilities.supportedCipherSuites = capabilities.supportedCipherSuites;

            mWifiAwareStateManager.onCapabilitiesUpdateResponse(id, frameworkCapabilities);
        } else {
            Log.e(TAG, "notifyCapabilitiesResponse: error code=" + status.status + " ("
                    + status.description + ")");
        }
    }

    @Override
    public void notifyEnableResponse(short id, WifiNanStatus status) {
        if (VDBG) Log.v(TAG, "notifyEnableResponse: id=" + id + ", status=" + statusString(status));

        if (status.status == NanStatusType.ALREADY_ENABLED) {
            Log.wtf(TAG, "notifyEnableResponse: id=" + id + ", already enabled!?");
        }

        if (status.status == NanStatusType.SUCCESS
                || status.status == NanStatusType.ALREADY_ENABLED) {
            mWifiAwareStateManager.onConfigSuccessResponse(id);
        } else {
            mWifiAwareStateManager.onConfigFailedResponse(id, status.status);
        }
    }

    @Override
    public void notifyConfigResponse(short id, WifiNanStatus status) {
        if (VDBG) Log.v(TAG, "notifyConfigResponse: id=" + id + ", status=" + statusString(status));

        if (status.status == NanStatusType.SUCCESS) {
            mWifiAwareStateManager.onConfigSuccessResponse(id);
        } else {
            mWifiAwareStateManager.onConfigFailedResponse(id, status.status);
        }
    }

    @Override
    public void notifyDisableResponse(short id, WifiNanStatus status) {
        if (VDBG) {
            Log.v(TAG, "notifyDisableResponse: id=" + id + ", status=" + statusString(status));
        }

        if (status.status != NanStatusType.SUCCESS) {
            Log.e(TAG, "notifyDisableResponse: failure - code=" + status.status + " ("
                    + status.description + ")");
        }
        mWifiAwareStateManager.onDisableResponse(id, status.status);
    }

    @Override
    public void notifyStartPublishResponse(short id, WifiNanStatus status, byte publishId) {
        if (VDBG) {
            Log.v(TAG, "notifyStartPublishResponse: id=" + id + ", status=" + statusString(status)
                    + ", publishId=" + publishId);
        }

        if (status.status == NanStatusType.SUCCESS) {
            mWifiAwareStateManager.onSessionConfigSuccessResponse(id, true, publishId);
        } else {
            mWifiAwareStateManager.onSessionConfigFailResponse(id, true, status.status);
        }
    }

    @Override
    public void notifyStopPublishResponse(short id, WifiNanStatus status) {
        if (VDBG) {
            Log.v(TAG, "notifyStopPublishResponse: id=" + id + ", status=" + statusString(status));
        }

        if (status.status == NanStatusType.SUCCESS) {
            // NOP
        } else {
            Log.e(TAG, "notifyStopPublishResponse: failure - code=" + status.status + " ("
                    + status.description + ")");
        }
    }

    @Override
    public void notifyStartSubscribeResponse(short id, WifiNanStatus status, byte subscribeId) {
        if (VDBG) {
            Log.v(TAG, "notifyStartSubscribeResponse: id=" + id + ", status=" + statusString(status)
                    + ", subscribeId=" + subscribeId);
        }

        if (status.status == NanStatusType.SUCCESS) {
            mWifiAwareStateManager.onSessionConfigSuccessResponse(id, false, subscribeId);
        } else {
            mWifiAwareStateManager.onSessionConfigFailResponse(id, false, status.status);
        }
    }

    @Override
    public void notifyStopSubscribeResponse(short id, WifiNanStatus status) {
        if (VDBG) {
            Log.v(TAG, "notifyStopSubscribeResponse: id=" + id + ", status="
                    + statusString(status));
        }

        if (status.status == NanStatusType.SUCCESS) {
            // NOP
        } else {
            Log.e(TAG, "notifyStopSubscribeResponse: failure - code=" + status.status + " ("
                    + status.description + ")");
        }
    }

    @Override
    public void notifyTransmitFollowupResponse(short id, WifiNanStatus status) {
        if (VDBG) {
            Log.v(TAG, "notifyTransmitFollowupResponse: id=" + id + ", status="
                    + statusString(status));
        }

        if (status.status == NanStatusType.SUCCESS) {
            mWifiAwareStateManager.onMessageSendQueuedSuccessResponse(id);
        } else {
            mWifiAwareStateManager.onMessageSendQueuedFailResponse(id, status.status);
        }
    }

    @Override
    public void notifyCreateDataInterfaceResponse(short id, WifiNanStatus status) {
        if (VDBG) {
            Log.v(TAG, "notifyCreateDataInterfaceResponse: id=" + id + ", status="
                    + statusString(status));
        }

        mWifiAwareStateManager.onCreateDataPathInterfaceResponse(id,
                status.status == NanStatusType.SUCCESS, status.status);
    }

    @Override
    public void notifyDeleteDataInterfaceResponse(short id, WifiNanStatus status) {
        if (VDBG) {
            Log.v(TAG, "notifyDeleteDataInterfaceResponse: id=" + id + ", status="
                    + statusString(status));
        }

        mWifiAwareStateManager.onDeleteDataPathInterfaceResponse(id,
                status.status == NanStatusType.SUCCESS, status.status);
    }

    @Override
    public void notifyInitiateDataPathResponse(short id, WifiNanStatus status,
            int ndpInstanceId) {
        if (VDBG) {
            Log.v(TAG, "notifyInitiateDataPathResponse: id=" + id + ", status="
                    + statusString(status) + ", ndpInstanceId=" + ndpInstanceId);
        }

        if (status.status == NanStatusType.SUCCESS) {
            mWifiAwareStateManager.onInitiateDataPathResponseSuccess(id, ndpInstanceId);
        } else {
            mWifiAwareStateManager.onInitiateDataPathResponseFail(id, status.status);
        }
    }

    @Override
    public void notifyRespondToDataPathIndicationResponse(short id, WifiNanStatus status) {
        if (VDBG) {
            Log.v(TAG, "notifyRespondToDataPathIndicationResponse: id=" + id
                    + ", status=" + statusString(status));
        }

        mWifiAwareStateManager.onRespondToDataPathSetupRequestResponse(id,
                status.status == NanStatusType.SUCCESS, status.status);
    }

    @Override
    public void notifyTerminateDataPathResponse(short id, WifiNanStatus status) {
        if (VDBG) {
            Log.v(TAG, "notifyTerminateDataPathResponse: id=" + id + ", status="
                    + statusString(status));
        }

        mWifiAwareStateManager.onEndDataPathResponse(id, status.status == NanStatusType.SUCCESS,
                status.status);
    }

    @Override
    public void eventClusterEvent(NanClusterEventInd event) {
        if (VDBG) {
            Log.v(TAG, "eventClusterEvent: eventType=" + event.eventType + ", addr="
                    + String.valueOf(HexEncoding.encode(event.addr)));
        }
        incrementCbCount(CB_EV_CLUSTER);

        if (event.eventType == NanClusterEventType.DISCOVERY_MAC_ADDRESS_CHANGED) {
            mWifiAwareStateManager.onInterfaceAddressChangeNotification(event.addr);
        } else if (event.eventType == NanClusterEventType.STARTED_CLUSTER) {
            mWifiAwareStateManager.onClusterChangeNotification(
                    WifiAwareClientState.CLUSTER_CHANGE_EVENT_STARTED, event.addr);
        } else if (event.eventType == NanClusterEventType.JOINED_CLUSTER) {
            mWifiAwareStateManager.onClusterChangeNotification(
                    WifiAwareClientState.CLUSTER_CHANGE_EVENT_JOINED, event.addr);
        } else {
            Log.e(TAG, "eventClusterEvent: invalid eventType=" + event.eventType);
        }
    }

    @Override
    public void eventDisabled(WifiNanStatus status) {
        if (VDBG) Log.v(TAG, "eventDisabled: status=" + statusString(status));
        incrementCbCount(CB_EV_DISABLED);

        mWifiAwareStateManager.onAwareDownNotification(status.status);
    }

    @Override
    public void eventPublishTerminated(byte sessionId, WifiNanStatus status) {
        if (VDBG) {
            Log.v(TAG, "eventPublishTerminated: sessionId=" + sessionId + ", status="
                    + statusString(status));
        }
        incrementCbCount(CB_EV_PUBLISH_TERMINATED);

        mWifiAwareStateManager.onSessionTerminatedNotification(sessionId, status.status, true);
    }

    @Override
    public void eventSubscribeTerminated(byte sessionId, WifiNanStatus status) {
        if (VDBG) {
            Log.v(TAG, "eventSubscribeTerminated: sessionId=" + sessionId + ", status="
                    + statusString(status));
        }
        incrementCbCount(CB_EV_SUBSCRIBE_TERMINATED);

        mWifiAwareStateManager.onSessionTerminatedNotification(sessionId, status.status, false);
    }

    @Override
    public void eventMatch(NanMatchInd event) {
        if (VDBG) {
            Log.v(TAG, "eventMatch: discoverySessionId=" + event.discoverySessionId + ", peerId="
                    + event.peerId + ", addr=" + String.valueOf(HexEncoding.encode(event.addr))
                    + ", serviceSpecificInfo=" + Arrays.toString(
                    convertArrayListToNativeByteArray(event.serviceSpecificInfo)) + ", ssi.size()="
                    + (event.serviceSpecificInfo == null ? 0 : event.serviceSpecificInfo.size())
                    + ", matchFilter=" + Arrays.toString(
                    convertArrayListToNativeByteArray(event.matchFilter)) + ", mf.size()=" + (
                    event.matchFilter == null ? 0 : event.matchFilter.size()));
        }
        incrementCbCount(CB_EV_MATCH);

        mWifiAwareStateManager.onMatchNotification(event.discoverySessionId, event.peerId,
                event.addr, convertArrayListToNativeByteArray(event.serviceSpecificInfo),
                convertArrayListToNativeByteArray(event.matchFilter));
    }

    @Override
    public void eventMatchExpired(byte discoverySessionId, int peerId) {
        if (VDBG) {
            Log.v(TAG, "eventMatchExpired: discoverySessionId=" + discoverySessionId
                    + ", peerId=" + peerId);
        }
        incrementCbCount(CB_EV_MATCH_EXPIRED);

        // NOP
    }

    @Override
    public void eventFollowupReceived(NanFollowupReceivedInd event) {
        if (VDBG) {
            Log.v(TAG, "eventFollowupReceived: discoverySessionId=" + event.discoverySessionId
                    + ", peerId=" + event.peerId + ", addr=" + String.valueOf(
                    HexEncoding.encode(event.addr)) + ", serviceSpecificInfo=" + Arrays.toString(
                    convertArrayListToNativeByteArray(event.serviceSpecificInfo)) + ", ssi.size()="
                    + (event.serviceSpecificInfo == null ? 0 : event.serviceSpecificInfo.size()));
        }
        incrementCbCount(CB_EV_FOLLOWUP_RECEIVED);

        mWifiAwareStateManager.onMessageReceivedNotification(event.discoverySessionId, event.peerId,
                event.addr, convertArrayListToNativeByteArray(event.serviceSpecificInfo));
    }

    @Override
    public void eventTransmitFollowup(short id, WifiNanStatus status) {
        if (VDBG) {
            Log.v(TAG, "eventTransmitFollowup: id=" + id + ", status=" + statusString(status));
        }
        incrementCbCount(CB_EV_TRANSMIT_FOLLOWUP);

        if (status.status == NanStatusType.SUCCESS) {
            mWifiAwareStateManager.onMessageSendSuccessNotification(id);
        } else {
            mWifiAwareStateManager.onMessageSendFailNotification(id, status.status);
        }
    }

    @Override
    public void eventDataPathRequest(NanDataPathRequestInd event) {
        if (VDBG) {
            Log.v(TAG, "eventDataPathRequest: discoverySessionId=" + event.discoverySessionId
                    + ", peerDiscMacAddr=" + String.valueOf(
                    HexEncoding.encode(event.peerDiscMacAddr)) + ", ndpInstanceId="
                    + event.ndpInstanceId);
        }
        incrementCbCount(CB_EV_DATA_PATH_REQUEST);

        mWifiAwareStateManager.onDataPathRequestNotification(event.discoverySessionId,
                event.peerDiscMacAddr, event.ndpInstanceId);
    }

    @Override
    public void eventDataPathConfirm(NanDataPathConfirmInd event) {
        if (VDBG) {
            Log.v(TAG, "onDataPathConfirm: ndpInstanceId=" + event.ndpInstanceId
                    + ", peerNdiMacAddr=" + String.valueOf(HexEncoding.encode(event.peerNdiMacAddr))
                    + ", dataPathSetupSuccess=" + event.dataPathSetupSuccess + ", reason="
                    + event.status.status);
        }
        incrementCbCount(CB_EV_DATA_PATH_CONFIRM);

        mWifiAwareStateManager.onDataPathConfirmNotification(event.ndpInstanceId,
                event.peerNdiMacAddr, event.dataPathSetupSuccess, event.status.status,
                convertArrayListToNativeByteArray(event.appInfo));
    }

    @Override
    public void eventDataPathTerminated(int ndpInstanceId) {
        if (VDBG) Log.v(TAG, "eventDataPathTerminated: ndpInstanceId=" + ndpInstanceId);
        incrementCbCount(CB_EV_DATA_PATH_TERMINATED);

        mWifiAwareStateManager.onDataPathEndNotification(ndpInstanceId);
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("WifiAwareNativeCallback:");
        pw.println("  mCallbackCounter: " + mCallbackCounter);
    }


    // utilities

    /**
     * Converts an ArrayList<Byte> to a byte[].
     *
     * @param from The input ArrayList<Byte></Byte> to convert from.
     *
     * @return A newly allocated byte[].
     */
    private byte[] convertArrayListToNativeByteArray(ArrayList<Byte> from) {
        if (from == null) {
            return null;
        }

        byte[] to = new byte[from.size()];
        for (int i = 0; i < from.size(); ++i) {
            to[i] = from.get(i);
        }
        return to;
    }

    private static String statusString(WifiNanStatus status) {
        if (status == null) {
            return "status=null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(status.status).append(" (").append(status.description).append(")");
        return sb.toString();
    }
}
