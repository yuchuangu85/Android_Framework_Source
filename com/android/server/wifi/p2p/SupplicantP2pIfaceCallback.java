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

package com.android.server.wifi.p2p;

import android.hardware.wifi.supplicant.V1_0.ISupplicantP2pIfaceCallback;
import android.hardware.wifi.supplicant.V1_0.WpsConfigMethods;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pProvDiscEvent;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceResponse;
import android.util.Log;

import com.android.server.wifi.p2p.WifiP2pServiceImpl.P2pStatus;
import com.android.server.wifi.util.NativeUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Class used for processing all P2P callbacks.
 */
public class SupplicantP2pIfaceCallback extends ISupplicantP2pIfaceCallback.Stub {
    private static final String TAG = "SupplicantP2pIfaceCallback";
    private static boolean sVerboseLoggingEnabled = true;

    private final String mInterface;
    private final WifiP2pMonitor mMonitor;

    public SupplicantP2pIfaceCallback(String iface, WifiP2pMonitor monitor) {
        mInterface = iface;
        mMonitor = monitor;
    }

    /**
     * Enable verbose logging for all sub modules.
     */
    public static void enableVerboseLogging(int verbose) {
        sVerboseLoggingEnabled = verbose > 0;
    }

    protected static void logd(String s) {
        if (sVerboseLoggingEnabled) Log.d(TAG, s);
    }

    /**
     * Used to indicate that a new network has been added.
     *
     * @param networkId Network ID allocated to the corresponding network.
     */
    public void onNetworkAdded(int networkId) {
    }


    /**
     * Used to indicate that a network has been removed.
     *
     * @param networkId Network ID allocated to the corresponding network.
     */
    public void onNetworkRemoved(int networkId) {
    }


    /**
     * Used to indicate that a P2P device has been found.
     *
     * @param srcAddress MAC address of the device found. This must either
     *        be the P2P device address or the P2P interface address.
     * @param p2pDeviceAddress P2P device address.
     * @param primaryDeviceType Type of device. Refer to section B.1 of Wifi P2P
     *        Technical specification v1.2.
     * @param deviceName Name of the device.
     * @param configMethods Mask of WPS configuration methods supported by the
     *        device.
     * @param deviceCapabilities Refer to section 4.1.4 of Wifi P2P Technical
     *        specification v1.2.
     * @param groupCapabilities Refer to section 4.1.4 of Wifi P2P Technical
     *        specification v1.2.
     * @param wfdDeviceInfo WFD device info as described in section 5.1.2 of WFD
     *        technical specification v1.0.0.
     */
    public void onDeviceFound(byte[] srcAddress, byte[] p2pDeviceAddress, byte[] primaryDeviceType,
            String deviceName, short configMethods, byte deviceCapabilities, int groupCapabilities,
            byte[] wfdDeviceInfo) {
        WifiP2pDevice device = new WifiP2pDevice();
        device.deviceName = deviceName;
        if (deviceName == null) {
            Log.e(TAG, "Missing device name.");
            return;
        }

        try {
            device.deviceAddress = NativeUtil.macAddressFromByteArray(p2pDeviceAddress);
        } catch (Exception e) {
            Log.e(TAG, "Could not decode device address.", e);
            return;
        }

        try {
            device.primaryDeviceType = NativeUtil.wpsDevTypeStringFromByteArray(primaryDeviceType);
        } catch (Exception e) {
            Log.e(TAG, "Could not encode device primary type.", e);
            return;
        }

        device.deviceCapability = deviceCapabilities;
        device.groupCapability = groupCapabilities;
        device.wpsConfigMethodsSupported = configMethods;
        device.status = WifiP2pDevice.AVAILABLE;

        if (wfdDeviceInfo != null && wfdDeviceInfo.length >= 6) {
            device.wfdInfo = new WifiP2pWfdInfo(
                    ((wfdDeviceInfo[0] & 0xFF) << 8) + (wfdDeviceInfo[1] & 0xFF),
                    ((wfdDeviceInfo[2] & 0xFF) << 8) + (wfdDeviceInfo[3] & 0xFF),
                    ((wfdDeviceInfo[4] & 0xFF) << 8) + (wfdDeviceInfo[5] & 0xFF));
        }

        logd("Device discovered on " + mInterface + ": " + device);
        mMonitor.broadcastP2pDeviceFound(mInterface, device);
    }

    /**
     * Used to indicate that a P2P device has been lost.
     *
     * @param p2pDeviceAddress P2P device address.
     */
    public void onDeviceLost(byte[] p2pDeviceAddress) {
        WifiP2pDevice device = new WifiP2pDevice();

        try {
            device.deviceAddress = NativeUtil.macAddressFromByteArray(p2pDeviceAddress);
        } catch (Exception e) {
            Log.e(TAG, "Could not decode device address.", e);
            return;
        }

        device.status = WifiP2pDevice.UNAVAILABLE;

        logd("Device lost on " + mInterface + ": " + device);
        mMonitor.broadcastP2pDeviceLost(mInterface, device);
    }


    /**
     * Used to indicate the termination of P2P find operation.
     */
    public void onFindStopped() {
        logd("Search stopped on " + mInterface);
        mMonitor.broadcastP2pFindStopped(mInterface);
    }


    /**
     * Used to indicate the reception of a P2P Group Owner negotiation request.
     *
     * @param srcAddress MAC address of the device that initiated the GO
     *        negotiation request.
     * @param passwordId Type of password.
     */
    public void onGoNegotiationRequest(byte[] srcAddress, short passwordId) {
        WifiP2pConfig config = new WifiP2pConfig();

        try {
            config.deviceAddress = NativeUtil.macAddressFromByteArray(srcAddress);
        } catch (Exception e) {
            Log.e(TAG, "Could not decode device address.", e);
            return;
        }

        config.wps = new WpsInfo();

        switch (passwordId) {
            case WpsDevPasswordId.USER_SPECIFIED:
                config.wps.setup = WpsInfo.DISPLAY;
                break;

            case WpsDevPasswordId.PUSHBUTTON:
                config.wps.setup = WpsInfo.PBC;
                break;

            case WpsDevPasswordId.REGISTRAR_SPECIFIED:
                config.wps.setup = WpsInfo.KEYPAD;
                break;

            default:
                config.wps.setup = WpsInfo.PBC;
                break;
        }

        logd("Group Owner negotiation initiated on " + mInterface + ": " + config);
        mMonitor.broadcastP2pGoNegotiationRequest(mInterface, config);
    }


    /**
     * Used to indicate the completion of a P2P Group Owner negotiation request.
     *
     * @param status Status of the GO negotiation.
     */
    public void onGoNegotiationCompleted(int status) {
        logd("Group Owner negotiation completed with status: " + status);
        P2pStatus result = halStatusToP2pStatus(status);

        if (result == P2pStatus.SUCCESS) {
            mMonitor.broadcastP2pGoNegotiationSuccess(mInterface);
        } else {
            mMonitor.broadcastP2pGoNegotiationFailure(mInterface, result);
        }
    }


    /**
     * Used to indicate a successful formation of a P2P group.
     */
    public void onGroupFormationSuccess() {
        logd("Group formation successful on " + mInterface);
        mMonitor.broadcastP2pGroupFormationSuccess(mInterface);
    }


    /**
     * Used to indicate a failure to form a P2P group.
     *
     * @param failureReason Failure reason string for debug purposes.
     */
    public void onGroupFormationFailure(String failureReason) {
        // TODO(ender): failureReason should probably be an int (P2pStatusCode).
        logd("Group formation failed on " + mInterface + ": " + failureReason);
        mMonitor.broadcastP2pGroupFormationFailure(mInterface, failureReason);
    }


    /**
     * Used to indicate the start of a P2P group.
     *
     * @param groupIfName Interface name of the group. (For ex: p2p-p2p0-1)
     * @param isGo Whether this device is owner of the group.
     * @param ssid SSID of the group.
     * @param frequency Frequency on which this group is created.
     * @param psk PSK used to secure the group.
     * @param passphrase PSK passphrase used to secure the group.
     * @param goDeviceAddress MAC Address of the owner of this group.
     * @param isPersistent Whether this group is persisted or not.
     */
    public void onGroupStarted(String groupIfName, boolean isGo, ArrayList<Byte> ssid,
            int frequency, byte[] psk, String passphrase, byte[] goDeviceAddress,
            boolean isPersistent) {
        if (groupIfName == null) {
            Log.e(TAG, "Missing group interface name.");
            return;
        }

        logd("Group " + groupIfName + " started on " + mInterface);

        WifiP2pGroup group = new WifiP2pGroup();
        group.setInterface(groupIfName);

        try {
            String quotedSsid = NativeUtil.encodeSsid(ssid);
            group.setNetworkName(NativeUtil.removeEnclosingQuotes(quotedSsid));
        } catch (Exception e) {
            Log.e(TAG, "Could not encode SSID.", e);
            return;
        }

        group.setFrequency(frequency);
        group.setIsGroupOwner(isGo);
        group.setPassphrase(passphrase);

        if (isPersistent) {
            group.setNetworkId(WifiP2pGroup.NETWORK_ID_PERSISTENT);
        } else {
            group.setNetworkId(WifiP2pGroup.NETWORK_ID_TEMPORARY);
        }

        WifiP2pDevice owner = new WifiP2pDevice();

        try {
            owner.deviceAddress = NativeUtil.macAddressFromByteArray(goDeviceAddress);
        } catch (Exception e) {
            Log.e(TAG, "Could not decode Group Owner address.", e);
            return;
        }

        group.setOwner(owner);
        mMonitor.broadcastP2pGroupStarted(mInterface, group);
    }


    /**
     * Used to indicate the removal of a P2P group.
     *
     * @param groupIfName Interface name of the group. (For ex: p2p-p2p0-1)
     * @param isGo Whether this device is owner of the group.
     */
    public void onGroupRemoved(String groupIfName, boolean isGo) {
        if (groupIfName == null) {
            Log.e(TAG, "Missing group name.");
            return;
        }

        logd("Group " + groupIfName + " removed from " + mInterface);
        WifiP2pGroup group = new WifiP2pGroup();
        group.setInterface(groupIfName);
        group.setIsGroupOwner(isGo);
        mMonitor.broadcastP2pGroupRemoved(mInterface, group);
    }


    /**
     * Used to indicate the reception of a P2P invitation.
     *
     * @param srcAddress MAC address of the device that sent the invitation.
     * @param goDeviceAddress MAC Address of the owner of this group.
     * @param bssid Bssid of the group.
     * @param persistentNetworkId Persistent network Id of the group.
     * @param operatingFrequency Frequency on which the invitation was received.
     */
    public void onInvitationReceived(byte[] srcAddress, byte[] goDeviceAddress,
            byte[] bssid, int persistentNetworkId, int operatingFrequency) {
        WifiP2pGroup group = new WifiP2pGroup();
        group.setNetworkId(persistentNetworkId);

        WifiP2pDevice client = new WifiP2pDevice();

        try {
            client.deviceAddress = NativeUtil.macAddressFromByteArray(srcAddress);
        } catch (Exception e) {
            Log.e(TAG, "Could not decode MAC address.", e);
            return;
        }

        group.addClient(client);

        WifiP2pDevice owner = new WifiP2pDevice();

        try {
            owner.deviceAddress = NativeUtil.macAddressFromByteArray(goDeviceAddress);
        } catch (Exception e) {
            Log.e(TAG, "Could not decode Group Owner MAC address.", e);
            return;
        }

        group.setOwner(owner);

        logd("Invitation received on " + mInterface + ": " + group);
        mMonitor.broadcastP2pInvitationReceived(mInterface, group);
    }


    /**
     * Used to indicate the result of the P2P invitation request.
     *
     * @param bssid Bssid of the group.
     * @param status Status of the invitation.
     */
    public void onInvitationResult(byte[] bssid, int status) {
        logd("Invitation completed with status: " + status);
        mMonitor.broadcastP2pInvitationResult(mInterface, halStatusToP2pStatus(status));
    }


    /**
     * Used to indicate the completion of a P2P provision discovery request.
     *
     * @param p2pDeviceAddress P2P device address.
     * @param isRequest Whether we received or sent the provision discovery.
     * @param status Status of the provision discovery (SupplicantStatusCode).
     * @param configMethods Mask of WPS configuration methods supported.
     *                      Only one configMethod bit should be set per call.
     * @param generatedPin 8 digit pin generated.
     */
    public void onProvisionDiscoveryCompleted(byte[] p2pDeviceAddress, boolean isRequest,
            byte status, short configMethods, String generatedPin) {
        if (status != ISupplicantP2pIfaceCallback.P2pProvDiscStatusCode.SUCCESS) {
            Log.e(TAG, "Provision discovery failed: " + status);
            mMonitor.broadcastP2pProvisionDiscoveryFailure(mInterface);
            return;
        }

        logd("Provision discovery " + (isRequest ? "request" : "response")
                + " for WPS Config method: " + configMethods);

        WifiP2pProvDiscEvent event = new WifiP2pProvDiscEvent();
        event.device = new WifiP2pDevice();

        try {
            event.device.deviceAddress = NativeUtil.macAddressFromByteArray(p2pDeviceAddress);
        } catch (Exception e) {
            Log.e(TAG, "Could not decode MAC address.", e);
            return;
        }

        if ((configMethods & WpsConfigMethods.PUSHBUTTON) != 0) {
            if (isRequest) {
                event.event = WifiP2pProvDiscEvent.PBC_REQ;
                mMonitor.broadcastP2pProvisionDiscoveryPbcRequest(mInterface, event);
            } else {
                event.event = WifiP2pProvDiscEvent.PBC_RSP;
                mMonitor.broadcastP2pProvisionDiscoveryPbcResponse(mInterface, event);
            }
        } else if (!isRequest && (configMethods & WpsConfigMethods.KEYPAD) != 0) {
            event.event = WifiP2pProvDiscEvent.SHOW_PIN;
            event.pin = generatedPin;
            mMonitor.broadcastP2pProvisionDiscoveryShowPin(mInterface, event);
        } else if (!isRequest && (configMethods & WpsConfigMethods.DISPLAY) != 0) {
            event.event = WifiP2pProvDiscEvent.ENTER_PIN;
            mMonitor.broadcastP2pProvisionDiscoveryEnterPin(mInterface, event);
        } else if (isRequest && (configMethods & WpsConfigMethods.DISPLAY) != 0) {
            event.event = WifiP2pProvDiscEvent.SHOW_PIN;
            event.pin = generatedPin;
            mMonitor.broadcastP2pProvisionDiscoveryShowPin(mInterface, event);
        } else if (isRequest && (configMethods & WpsConfigMethods.KEYPAD) != 0) {
            event.event = WifiP2pProvDiscEvent.ENTER_PIN;
            mMonitor.broadcastP2pProvisionDiscoveryEnterPin(mInterface, event);
        } else {
            Log.e(TAG, "Unsupported config methods: " + configMethods);
        }
    }


    /**
     * Used to indicate the reception of a P2P service discovery response.
     *
     * @param srcAddress MAC address of the device that sent the service discovery.
     * @param updateIndicator Service update indicator. Refer to section 3.1.3 of
     *        Wifi P2P Technical specification v1.2.
     * @param tlvs Refer to section 3.1.3.1 of Wifi P2P Technical specification v1.2.
     */
    public void onServiceDiscoveryResponse(byte[] srcAddress, short updateIndicator,
            ArrayList<Byte> tlvs) {
        List<WifiP2pServiceResponse> response = null;

        logd("Service discovery response received on " + mInterface);
        try {
            String srcAddressStr = NativeUtil.macAddressFromByteArray(srcAddress);
            // updateIndicator is not used
            response = WifiP2pServiceResponse.newInstance(srcAddressStr,
                    NativeUtil.byteArrayFromArrayList(tlvs));
        } catch (Exception e) {
            Log.e(TAG, "Could not process service discovery response.", e);
            return;
        }
        mMonitor.broadcastP2pServiceDiscoveryResponse(mInterface, response);
    }

    private WifiP2pDevice createStaEventDevice(byte[] srcAddress, byte[] p2pDeviceAddress) {
        WifiP2pDevice device = new WifiP2pDevice();
        byte[] deviceAddressBytes;
        // Legacy STAs may not supply a p2pDeviceAddress (signaled by a zero'd p2pDeviceAddress)
        // In this case, use srcAddress instead
        if (!Arrays.equals(NativeUtil.ANY_MAC_BYTES, p2pDeviceAddress)) {
            deviceAddressBytes = p2pDeviceAddress;
        } else {
            deviceAddressBytes = srcAddress;
        }
        try {
            device.deviceAddress = NativeUtil.macAddressFromByteArray(deviceAddressBytes);
        } catch (Exception e) {
            Log.e(TAG, "Could not decode MAC address", e);
            return null;
        }
        return device;
    }

    /**
     * Used to indicate when a STA device is connected to this device.
     *
     * @param srcAddress MAC address of the device that was authorized.
     * @param p2pDeviceAddress P2P device address.
     */
    public void onStaAuthorized(byte[] srcAddress, byte[] p2pDeviceAddress) {
        logd("STA authorized on " + mInterface);
        WifiP2pDevice device = createStaEventDevice(srcAddress, p2pDeviceAddress);
        if (device == null) {
            return;
        }
        mMonitor.broadcastP2pApStaConnected(mInterface, device);
    }


    /**
     * Used to indicate when a STA device is disconnected from this device.
     *
     * @param srcAddress MAC address of the device that was deauthorized.
     * @param p2pDeviceAddress P2P device address.
     */
    public void onStaDeauthorized(byte[] srcAddress, byte[] p2pDeviceAddress) {
        logd("STA deauthorized on " + mInterface);
        WifiP2pDevice device = createStaEventDevice(srcAddress, p2pDeviceAddress);
        if (device == null) {
            return;
        }
        mMonitor.broadcastP2pApStaDisconnected(mInterface, device);
    }


    private static P2pStatus halStatusToP2pStatus(int status) {
        P2pStatus result = P2pStatus.UNKNOWN;

        switch (status) {
            case P2pStatusCode.SUCCESS:
            case P2pStatusCode.SUCCESS_DEFERRED:
                result = P2pStatus.SUCCESS;
                break;

            case P2pStatusCode.FAIL_INFO_CURRENTLY_UNAVAILABLE:
                result = P2pStatus.INFORMATION_IS_CURRENTLY_UNAVAILABLE;
                break;

            case P2pStatusCode.FAIL_INCOMPATIBLE_PARAMS:
                result = P2pStatus.INCOMPATIBLE_PARAMETERS;
                break;

            case P2pStatusCode.FAIL_LIMIT_REACHED:
                result = P2pStatus.LIMIT_REACHED;
                break;

            case P2pStatusCode.FAIL_INVALID_PARAMS:
                result = P2pStatus.INVALID_PARAMETER;
                break;

            case P2pStatusCode.FAIL_UNABLE_TO_ACCOMMODATE:
                result = P2pStatus.UNABLE_TO_ACCOMMODATE_REQUEST;
                break;

            case P2pStatusCode.FAIL_PREV_PROTOCOL_ERROR:
                result = P2pStatus.PREVIOUS_PROTOCOL_ERROR;
                break;

            case P2pStatusCode.FAIL_NO_COMMON_CHANNELS:
                result = P2pStatus.NO_COMMON_CHANNEL;
                break;

            case P2pStatusCode.FAIL_UNKNOWN_GROUP:
                result = P2pStatus.UNKNOWN_P2P_GROUP;
                break;

            case P2pStatusCode.FAIL_BOTH_GO_INTENT_15:
                result = P2pStatus.BOTH_GO_INTENT_15;
                break;

            case P2pStatusCode.FAIL_INCOMPATIBLE_PROV_METHOD:
                result = P2pStatus.INCOMPATIBLE_PROVISIONING_METHOD;
                break;

            case P2pStatusCode.FAIL_REJECTED_BY_USER:
                result = P2pStatus.REJECTED_BY_USER;
                break;
        }
        return result;
    }
}

