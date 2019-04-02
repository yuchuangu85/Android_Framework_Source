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
import android.util.Log;

import libcore.util.HexEncoding;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Manages the callbacks from Wi-Fi Aware HIDL (HAL).
 */
public class WifiAwareNativeCallback extends IWifiNanIfaceEventCallback.Stub {
    private static final String TAG = "WifiAwareNativeCallback";
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    private final WifiAwareStateManager mWifiAwareStateManager;

    public WifiAwareNativeCallback(WifiAwareStateManager wifiAwareStateManager) {
        mWifiAwareStateManager = wifiAwareStateManager;
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

        if (status.status == NanStatusType.SUCCESS) {
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

        if (status.status == NanStatusType.SUCCESS) {
            // NOP
        } else {
            Log.e(TAG, "notifyDisableResponse: failure - code=" + status.status + " ("
                    + status.description + ")");
        }
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

        mWifiAwareStateManager.onAwareDownNotification(status.status);
    }

    @Override
    public void eventPublishTerminated(byte sessionId, WifiNanStatus status) {
        if (VDBG) {
            Log.v(TAG, "eventPublishTerminated: sessionId=" + sessionId + ", status="
                    + statusString(status));
        }

        mWifiAwareStateManager.onSessionTerminatedNotification(sessionId, status.status, true);
    }

    @Override
    public void eventSubscribeTerminated(byte sessionId, WifiNanStatus status) {
        if (VDBG) {
            Log.v(TAG, "eventSubscribeTerminated: sessionId=" + sessionId + ", status="
                    + statusString(status));
        }

        mWifiAwareStateManager.onSessionTerminatedNotification(sessionId, status.status, false);
    }

    @Override
    public void eventMatch(NanMatchInd event) {
        if (VDBG) {
            Log.v(TAG, "eventMatch: discoverySessionId=" + event.discoverySessionId + ", peerId="
                    + event.peerId + ", addr=" + String.valueOf(HexEncoding.encode(event.addr))
                    + ", serviceSpecificInfo=" + Arrays.toString(
                    convertArrayListToNativeByteArray(event.serviceSpecificInfo)) + ", matchFilter="
                    + Arrays.toString(convertArrayListToNativeByteArray(event.matchFilter)));
        }

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

        // NOP
    }

    @Override
    public void eventFollowupReceived(NanFollowupReceivedInd event) {
        if (VDBG) {
            Log.v(TAG, "eventFollowupReceived: discoverySessionId=" + event.discoverySessionId
                    + ", peerId=" + event.peerId + ", addr=" + String.valueOf(
                    HexEncoding.encode(event.addr)));
        }

        mWifiAwareStateManager.onMessageReceivedNotification(event.discoverySessionId, event.peerId,
                event.addr, convertArrayListToNativeByteArray(event.serviceSpecificInfo));
    }

    @Override
    public void eventTransmitFollowup(short id, WifiNanStatus status) {
        if (VDBG) {
            Log.v(TAG, "eventTransmitFollowup: id=" + id + ", status=" + statusString(status));
        }

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

        mWifiAwareStateManager.onDataPathConfirmNotification(event.ndpInstanceId,
                event.peerNdiMacAddr, event.dataPathSetupSuccess, event.status.status,
                convertArrayListToNativeByteArray(event.appInfo));
    }

    @Override
    public void eventDataPathTerminated(int ndpInstanceId) {
        if (VDBG) Log.v(TAG, "eventDataPathTerminated: ndpInstanceId=" + ndpInstanceId);

        mWifiAwareStateManager.onDataPathEndNotification(ndpInstanceId);
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
