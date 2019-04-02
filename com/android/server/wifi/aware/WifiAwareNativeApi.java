/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.hardware.wifi.V1_0.IWifiNanIface;
import android.hardware.wifi.V1_0.NanBandIndex;
import android.hardware.wifi.V1_0.NanBandSpecificConfig;
import android.hardware.wifi.V1_0.NanCipherSuiteType;
import android.hardware.wifi.V1_0.NanConfigRequest;
import android.hardware.wifi.V1_0.NanDataPathSecurityType;
import android.hardware.wifi.V1_0.NanEnableRequest;
import android.hardware.wifi.V1_0.NanInitiateDataPathRequest;
import android.hardware.wifi.V1_0.NanMatchAlg;
import android.hardware.wifi.V1_0.NanPublishRequest;
import android.hardware.wifi.V1_0.NanRespondToDataPathIndicationRequest;
import android.hardware.wifi.V1_0.NanSubscribeRequest;
import android.hardware.wifi.V1_0.NanTransmitFollowupRequest;
import android.hardware.wifi.V1_0.NanTxType;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.net.wifi.aware.ConfigRequest;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.SubscribeConfig;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.util.Log;

import libcore.util.HexEncoding;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Translates Wi-Fi Aware requests from the framework to the HAL (HIDL).
 *
 * Delegates the management of the NAN interface to WifiAwareNativeManager.
 */
public class WifiAwareNativeApi implements WifiAwareShellCommand.DelegatedShellCommand {
    private static final String TAG = "WifiAwareNativeApi";
    private static final boolean DBG = false;
    private static final boolean VDBG = false; // STOPSHIP if true

    private static final String SERVICE_NAME_FOR_OOB_DATA_PATH = "Wi-Fi Aware Data Path";

    private final WifiAwareNativeManager mHal;

    public WifiAwareNativeApi(WifiAwareNativeManager wifiAwareNativeManager) {
        mHal = wifiAwareNativeManager;
        onReset();
    }

    /*
     * Parameters settable through the shell command.
     * see wifi/1.0/types.hal NanBandSpecificConfig.discoveryWindowIntervalVal for description
     */
    public static final String PARAM_DW_DEFAULT_24GHZ = "dw_default_24ghz";
    public static final int PARAM_DW_DEFAULT_24GHZ_DEFAULT = -1; // Firmware default
    public static final String PARAM_DW_DEFAULT_5GHZ = "dw_default_5ghz";
    public static final int PARAM_DW_DEFAULT_5GHZ_DEFAULT = -1; // Firmware default
    public static final String PARAM_DW_ON_INACTIVE_24GHZ = "dw_on_inactive_24ghz";
    public static final int PARAM_DW_ON_INACTIVE_24GHZ_DEFAULT = 4; // 4 -> DW=8, latency=4s
    public static final String PARAM_DW_ON_INACTIVE_5GHZ = "dw_on_inactive_5ghz";
    public static final int PARAM_DW_ON_INACTIVE_5GHZ_DEFAULT = 0; // 0 = disabled
    public static final String PARAM_DW_ON_IDLE_24GHZ = "dw_on_idle_24ghz";
    public static final int PARAM_DW_ON_IDLE_24GHZ_DEFAULT = -1; // NOP (but disabling on IDLE)
    public static final String PARAM_DW_ON_IDLE_5GHZ = "dw_on_idle_5ghz";
    public static final int PARAM_DW_ON_IDLE_5GHZ_DEFAULT = -1; // NOP (but disabling on IDLE)

    public static final String PARAM_MAC_RANDOM_INTERVAL_SEC = "mac_random_interval_sec";
    public static final int PARAM_MAC_RANDOM_INTERVAL_SEC_DEFAULT = 1800; // 30 minutes

    private Map<String, Integer> mSettableParameters = new HashMap<>();

    /**
     * Interpreter of adb shell command 'adb shell wifiaware native_api ...'.
     *
     * @return -1 if parameter not recognized or invalid value, 0 otherwise.
     */
    @Override
    public int onCommand(ShellCommand parentShell) {
        final PrintWriter pw = parentShell.getErrPrintWriter();

        String subCmd = parentShell.getNextArgRequired();
        if (VDBG) Log.v(TAG, "onCommand: subCmd='" + subCmd + "'");
        switch (subCmd) {
            case "set": {
                String name = parentShell.getNextArgRequired();
                if (VDBG) Log.v(TAG, "onCommand: name='" + name + "'");
                if (!mSettableParameters.containsKey(name)) {
                    pw.println("Unknown parameter name -- '" + name + "'");
                    return -1;
                }

                String valueStr = parentShell.getNextArgRequired();
                if (VDBG) Log.v(TAG, "onCommand: valueStr='" + valueStr + "'");
                int value;
                try {
                    value = Integer.valueOf(valueStr);
                } catch (NumberFormatException e) {
                    pw.println("Can't convert value to integer -- '" + valueStr + "'");
                    return -1;
                }
                mSettableParameters.put(name, value);
                return 0;
            }
            case "get": {
                String name = parentShell.getNextArgRequired();
                if (VDBG) Log.v(TAG, "onCommand: name='" + name + "'");
                if (!mSettableParameters.containsKey(name)) {
                    pw.println("Unknown parameter name -- '" + name + "'");
                    return -1;
                }

                parentShell.getOutPrintWriter().println((int) mSettableParameters.get(name));
                return 0;
            }
            default:
                pw.println("Unknown 'wifiaware native_api <cmd>'");
        }

        return -1;
    }

    @Override
    public void onReset() {
        mSettableParameters.put(PARAM_DW_DEFAULT_24GHZ, PARAM_DW_DEFAULT_24GHZ_DEFAULT);
        mSettableParameters.put(PARAM_DW_DEFAULT_5GHZ, PARAM_DW_DEFAULT_5GHZ_DEFAULT);
        mSettableParameters.put(PARAM_DW_ON_INACTIVE_24GHZ, PARAM_DW_ON_INACTIVE_24GHZ_DEFAULT);
        mSettableParameters.put(PARAM_DW_ON_INACTIVE_5GHZ, PARAM_DW_ON_INACTIVE_5GHZ_DEFAULT);
        mSettableParameters.put(PARAM_DW_ON_IDLE_24GHZ, PARAM_DW_ON_IDLE_24GHZ_DEFAULT);
        mSettableParameters.put(PARAM_DW_ON_IDLE_5GHZ, PARAM_DW_ON_IDLE_5GHZ_DEFAULT);

        mSettableParameters.put(PARAM_MAC_RANDOM_INTERVAL_SEC,
                PARAM_MAC_RANDOM_INTERVAL_SEC_DEFAULT);
    }

    @Override
    public void onHelp(String command, ShellCommand parentShell) {
        final PrintWriter pw = parentShell.getOutPrintWriter();

        pw.println("  " + command);
        pw.println("    set <name> <value>: sets named parameter to value. Names: "
                + mSettableParameters.keySet());
        pw.println("    get <name>: gets named parameter value. Names: "
                + mSettableParameters.keySet());
    }

    /**
     * Query the firmware's capabilities.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     */
    public boolean getCapabilities(short transactionId) {
        if (VDBG) Log.v(TAG, "getCapabilities: transactionId=" + transactionId);

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "getCapabilities: null interface");
            return false;
        }

        try {
            WifiStatus status = iface.getCapabilitiesRequest(transactionId);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "getCapabilities: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getCapabilities: exception: " + e);
            return false;
        }
    }

    /**
     * Enable and configure Aware.
     *
     * @param transactionId Transaction ID for the transaction - used in the
     *            async callback to match with the original request.
     * @param configRequest Requested Aware configuration.
     * @param notifyIdentityChange Indicates whether or not to get address change callbacks.
     * @param initialConfiguration Specifies whether initial configuration
     *            (true) or an update (false) to the configuration.
     * @param isInteractive PowerManager.isInteractive
     * @param isIdle PowerManager.isIdle
     */
    public boolean enableAndConfigure(short transactionId, ConfigRequest configRequest,
            boolean notifyIdentityChange, boolean initialConfiguration, boolean isInteractive,
            boolean isIdle) {
        if (VDBG) {
            Log.v(TAG, "enableAndConfigure: transactionId=" + transactionId + ", configRequest="
                    + configRequest + ", notifyIdentityChange=" + notifyIdentityChange
                    + ", initialConfiguration=" + initialConfiguration
                    + ", isInteractive=" + isInteractive + ", isIdle=" + isIdle);
        }

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "enableAndConfigure: null interface");
            return false;
        }

        try {
            WifiStatus status;
            if (initialConfiguration) {
                // translate framework to HIDL configuration
                NanEnableRequest req = new NanEnableRequest();

                req.operateInBand[NanBandIndex.NAN_BAND_24GHZ] = true;
                req.operateInBand[NanBandIndex.NAN_BAND_5GHZ] = configRequest.mSupport5gBand;
                req.hopCountMax = 2;
                req.configParams.masterPref = (byte) configRequest.mMasterPreference;
                req.configParams.disableDiscoveryAddressChangeIndication = !notifyIdentityChange;
                req.configParams.disableStartedClusterIndication = !notifyIdentityChange;
                req.configParams.disableJoinedClusterIndication = !notifyIdentityChange;
                req.configParams.includePublishServiceIdsInBeacon = true;
                req.configParams.numberOfPublishServiceIdsInBeacon = 0;
                req.configParams.includeSubscribeServiceIdsInBeacon = true;
                req.configParams.numberOfSubscribeServiceIdsInBeacon = 0;
                req.configParams.rssiWindowSize = 8;
                req.configParams.macAddressRandomizationIntervalSec = mSettableParameters.get(
                        PARAM_MAC_RANDOM_INTERVAL_SEC);

                NanBandSpecificConfig config24 = new NanBandSpecificConfig();
                config24.rssiClose = 60;
                config24.rssiMiddle = 70;
                config24.rssiCloseProximity = 60;
                config24.dwellTimeMs = (byte) 200;
                config24.scanPeriodSec = 20;
                if (configRequest.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_24GHZ]
                        == ConfigRequest.DW_INTERVAL_NOT_INIT) {
                    config24.validDiscoveryWindowIntervalVal = false;
                } else {
                    config24.validDiscoveryWindowIntervalVal = true;
                    config24.discoveryWindowIntervalVal =
                            (byte) configRequest.mDiscoveryWindowInterval[ConfigRequest
                                    .NAN_BAND_24GHZ];
                }
                req.configParams.bandSpecificConfig[NanBandIndex.NAN_BAND_24GHZ] = config24;

                NanBandSpecificConfig config5 = new NanBandSpecificConfig();
                config5.rssiClose = 60;
                config5.rssiMiddle = 75;
                config5.rssiCloseProximity = 60;
                config5.dwellTimeMs = (byte) 200;
                config5.scanPeriodSec = 20;
                if (configRequest.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_5GHZ]
                        == ConfigRequest.DW_INTERVAL_NOT_INIT) {
                    config5.validDiscoveryWindowIntervalVal = false;
                } else {
                    config5.validDiscoveryWindowIntervalVal = true;
                    config5.discoveryWindowIntervalVal =
                            (byte) configRequest.mDiscoveryWindowInterval[ConfigRequest
                                    .NAN_BAND_5GHZ];
                }
                req.configParams.bandSpecificConfig[NanBandIndex.NAN_BAND_5GHZ] = config5;

                req.debugConfigs.validClusterIdVals = true;
                req.debugConfigs.clusterIdTopRangeVal = (short) configRequest.mClusterHigh;
                req.debugConfigs.clusterIdBottomRangeVal = (short) configRequest.mClusterLow;
                req.debugConfigs.validIntfAddrVal = false;
                req.debugConfigs.validOuiVal = false;
                req.debugConfigs.ouiVal = 0;
                req.debugConfigs.validRandomFactorForceVal = false;
                req.debugConfigs.randomFactorForceVal = 0;
                req.debugConfigs.validHopCountForceVal = false;
                req.debugConfigs.hopCountForceVal = 0;
                req.debugConfigs.validDiscoveryChannelVal = false;
                req.debugConfigs.discoveryChannelMhzVal[NanBandIndex.NAN_BAND_24GHZ] = 0;
                req.debugConfigs.discoveryChannelMhzVal[NanBandIndex.NAN_BAND_5GHZ] = 0;
                req.debugConfigs.validUseBeaconsInBandVal = false;
                req.debugConfigs.useBeaconsInBandVal[NanBandIndex.NAN_BAND_24GHZ] = true;
                req.debugConfigs.useBeaconsInBandVal[NanBandIndex.NAN_BAND_5GHZ] = true;
                req.debugConfigs.validUseSdfInBandVal = false;
                req.debugConfigs.useSdfInBandVal[NanBandIndex.NAN_BAND_24GHZ] = true;
                req.debugConfigs.useSdfInBandVal[NanBandIndex.NAN_BAND_5GHZ] = true;

                updateConfigForPowerSettings(req.configParams, isInteractive, isIdle);

                status = iface.enableRequest(transactionId, req);
            } else {
                NanConfigRequest req = new NanConfigRequest();
                req.masterPref = (byte) configRequest.mMasterPreference;
                req.disableDiscoveryAddressChangeIndication = !notifyIdentityChange;
                req.disableStartedClusterIndication = !notifyIdentityChange;
                req.disableJoinedClusterIndication = !notifyIdentityChange;
                req.includePublishServiceIdsInBeacon = true;
                req.numberOfPublishServiceIdsInBeacon = 0;
                req.includeSubscribeServiceIdsInBeacon = true;
                req.numberOfSubscribeServiceIdsInBeacon = 0;
                req.rssiWindowSize = 8;
                req.macAddressRandomizationIntervalSec = mSettableParameters.get(
                        PARAM_MAC_RANDOM_INTERVAL_SEC);

                NanBandSpecificConfig config24 = new NanBandSpecificConfig();
                config24.rssiClose = 60;
                config24.rssiMiddle = 70;
                config24.rssiCloseProximity = 60;
                config24.dwellTimeMs = (byte) 200;
                config24.scanPeriodSec = 20;
                if (configRequest.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_24GHZ]
                        == ConfigRequest.DW_INTERVAL_NOT_INIT) {
                    config24.validDiscoveryWindowIntervalVal = false;
                } else {
                    config24.validDiscoveryWindowIntervalVal = true;
                    config24.discoveryWindowIntervalVal =
                            (byte) configRequest.mDiscoveryWindowInterval[ConfigRequest
                                    .NAN_BAND_24GHZ];
                }
                req.bandSpecificConfig[NanBandIndex.NAN_BAND_24GHZ] = config24;

                NanBandSpecificConfig config5 = new NanBandSpecificConfig();
                config5.rssiClose = 60;
                config5.rssiMiddle = 75;
                config5.rssiCloseProximity = 60;
                config5.dwellTimeMs = (byte) 200;
                config5.scanPeriodSec = 20;
                if (configRequest.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_5GHZ]
                        == ConfigRequest.DW_INTERVAL_NOT_INIT) {
                    config5.validDiscoveryWindowIntervalVal = false;
                } else {
                    config5.validDiscoveryWindowIntervalVal = true;
                    config5.discoveryWindowIntervalVal =
                            (byte) configRequest.mDiscoveryWindowInterval[ConfigRequest
                                    .NAN_BAND_5GHZ];
                }
                req.bandSpecificConfig[NanBandIndex.NAN_BAND_5GHZ] = config5;

                updateConfigForPowerSettings(req, isInteractive, isIdle);

                status = iface.configRequest(transactionId, req);
            }
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "enableAndConfigure: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "enableAndConfigure: exception: " + e);
            return false;
        }
    }

    /**
     * Disable Aware.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     */
    public boolean disable(short transactionId) {
        if (VDBG) Log.d(TAG, "disable");

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "disable: null interface");
            return false;
        }

        try {
            WifiStatus status = iface.disableRequest(transactionId);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "disable: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "disable: exception: " + e);
            return false;
        }
    }

    /**
     * Start or modify a service publish session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param publishId ID of the requested session - 0 to request a new publish
     *            session.
     * @param publishConfig Configuration of the discovery session.
     */
    public boolean publish(short transactionId, byte publishId, PublishConfig publishConfig) {
        if (VDBG) {
            Log.d(TAG, "publish: transactionId=" + transactionId + ", publishId=" + publishId
                    + ", config=" + publishConfig);
        }

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "publish: null interface");
            return false;
        }

        NanPublishRequest req = new NanPublishRequest();
        req.baseConfigs.sessionId = publishId;
        req.baseConfigs.ttlSec = (short) publishConfig.mTtlSec;
        req.baseConfigs.discoveryWindowPeriod = 1;
        req.baseConfigs.discoveryCount = 0;
        convertNativeByteArrayToArrayList(publishConfig.mServiceName, req.baseConfigs.serviceName);
        req.baseConfigs.discoveryMatchIndicator = NanMatchAlg.MATCH_NEVER;
        convertNativeByteArrayToArrayList(publishConfig.mServiceSpecificInfo,
                req.baseConfigs.serviceSpecificInfo);
        convertNativeByteArrayToArrayList(publishConfig.mMatchFilter,
                publishConfig.mPublishType == PublishConfig.PUBLISH_TYPE_UNSOLICITED
                        ? req.baseConfigs.txMatchFilter : req.baseConfigs.rxMatchFilter);
        req.baseConfigs.useRssiThreshold = false;
        req.baseConfigs.disableDiscoveryTerminationIndication =
                !publishConfig.mEnableTerminateNotification;
        req.baseConfigs.disableMatchExpirationIndication = true;
        req.baseConfigs.disableFollowupReceivedIndication = false;

        // TODO: configure ranging and security
        req.baseConfigs.securityConfig.securityType = NanDataPathSecurityType.OPEN;
        req.baseConfigs.rangingRequired = false;
        req.autoAcceptDataPathRequests = false;

        req.publishType = publishConfig.mPublishType;
        req.txType = NanTxType.BROADCAST;

        try {
            WifiStatus status = iface.startPublishRequest(transactionId, req);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "publish: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "publish: exception: " + e);
            return false;
        }
    }

    /**
     * Start or modify a service subscription session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param subscribeId ID of the requested session - 0 to request a new
     *            subscribe session.
     * @param subscribeConfig Configuration of the discovery session.
     */
    public boolean subscribe(short transactionId, byte subscribeId,
            SubscribeConfig subscribeConfig) {
        if (VDBG) {
            Log.d(TAG, "subscribe: transactionId=" + transactionId + ", subscribeId=" + subscribeId
                    + ", config=" + subscribeConfig);
        }

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "subscribe: null interface");
            return false;
        }

        NanSubscribeRequest req = new NanSubscribeRequest();
        req.baseConfigs.sessionId = subscribeId;
        req.baseConfigs.ttlSec = (short) subscribeConfig.mTtlSec;
        req.baseConfigs.discoveryWindowPeriod = 1;
        req.baseConfigs.discoveryCount = 0;
        convertNativeByteArrayToArrayList(subscribeConfig.mServiceName,
                req.baseConfigs.serviceName);
        req.baseConfigs.discoveryMatchIndicator = NanMatchAlg.MATCH_ONCE;
        convertNativeByteArrayToArrayList(subscribeConfig.mServiceSpecificInfo,
                req.baseConfigs.serviceSpecificInfo);
        convertNativeByteArrayToArrayList(subscribeConfig.mMatchFilter,
                subscribeConfig.mSubscribeType == SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE
                        ? req.baseConfigs.txMatchFilter : req.baseConfigs.rxMatchFilter);
        req.baseConfigs.useRssiThreshold = false;
        req.baseConfigs.disableDiscoveryTerminationIndication =
                !subscribeConfig.mEnableTerminateNotification;
        req.baseConfigs.disableMatchExpirationIndication = true;
        req.baseConfigs.disableFollowupReceivedIndication = false;

        // TODO: configure ranging and security
        req.baseConfigs.securityConfig.securityType = NanDataPathSecurityType.OPEN;
        req.baseConfigs.rangingRequired = false;

        req.subscribeType = subscribeConfig.mSubscribeType;

        try {
            WifiStatus status = iface.startSubscribeRequest(transactionId, req);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "subscribe: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "subscribe: exception: " + e);
            return false;
        }
    }

    /**
     * Send a message through an existing discovery session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param pubSubId ID of the existing publish/subscribe session.
     * @param requestorInstanceId ID of the peer to communicate with - obtained
     *            through a previous discovery (match) operation with that peer.
     * @param dest MAC address of the peer to communicate with - obtained
     *            together with requestorInstanceId.
     * @param message Message.
     * @param messageId Arbitary integer from host (not sent to HAL - useful for
     *                  testing/debugging at this level)
     */
    public boolean sendMessage(short transactionId, byte pubSubId, int requestorInstanceId,
            byte[] dest, byte[] message, int messageId) {
        if (VDBG) {
            Log.d(TAG,
                    "sendMessage: transactionId=" + transactionId + ", pubSubId=" + pubSubId
                            + ", requestorInstanceId=" + requestorInstanceId + ", dest="
                            + String.valueOf(HexEncoding.encode(dest)) + ", messageId=" + messageId
                            + ", message=" + (message == null ? "<null>"
                            : HexEncoding.encode(message)) + ", message.length=" + (message == null
                            ? 0 : message.length));
        }

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "sendMessage: null interface");
            return false;
        }

        NanTransmitFollowupRequest req = new NanTransmitFollowupRequest();
        req.discoverySessionId = pubSubId;
        req.peerId = requestorInstanceId;
        copyArray(dest, req.addr);
        req.isHighPriority = false;
        req.shouldUseDiscoveryWindow = true;
        convertNativeByteArrayToArrayList(message, req.serviceSpecificInfo);
        req.disableFollowupResultIndication = false;

        try {
            WifiStatus status = iface.transmitFollowupRequest(transactionId, req);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "sendMessage: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "sendMessage: exception: " + e);
            return false;
        }
    }

    /**
     * Terminate a publish discovery session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param pubSubId ID of the publish/subscribe session - obtained when
     *            creating a session.
     */
    public boolean stopPublish(short transactionId, byte pubSubId) {
        if (VDBG) {
            Log.d(TAG, "stopPublish: transactionId=" + transactionId + ", pubSubId=" + pubSubId);
        }

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "stopPublish: null interface");
            return false;
        }

        try {
            WifiStatus status = iface.stopPublishRequest(transactionId, pubSubId);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "stopPublish: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "stopPublish: exception: " + e);
            return false;
        }
    }

    /**
     * Terminate a subscribe discovery session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param pubSubId ID of the publish/subscribe session - obtained when
     *            creating a session.
     */
    public boolean stopSubscribe(short transactionId, byte pubSubId) {
        if (VDBG) {
            Log.d(TAG, "stopSubscribe: transactionId=" + transactionId + ", pubSubId=" + pubSubId);
        }

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "stopSubscribe: null interface");
            return false;
        }

        try {
            WifiStatus status = iface.stopSubscribeRequest(transactionId, pubSubId);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "stopSubscribe: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "stopSubscribe: exception: " + e);
            return false;
        }
    }

    /**
     * Create a Aware network interface. This only creates the Linux interface - it doesn't actually
     * create the data connection.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param interfaceName The name of the interface, e.g. "aware0".
     */
    public boolean createAwareNetworkInterface(short transactionId, String interfaceName) {
        if (VDBG) {
            Log.v(TAG, "createAwareNetworkInterface: transactionId=" + transactionId + ", "
                    + "interfaceName=" + interfaceName);
        }

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "createAwareNetworkInterface: null interface");
            return false;
        }

        try {
            WifiStatus status = iface.createDataInterfaceRequest(transactionId, interfaceName);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "createAwareNetworkInterface: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "createAwareNetworkInterface: exception: " + e);
            return false;
        }
    }

    /**
     * Deletes a Aware network interface. The data connection can (should?) be torn down previously.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param interfaceName The name of the interface, e.g. "aware0".
     */
    public boolean deleteAwareNetworkInterface(short transactionId, String interfaceName) {
        if (VDBG) {
            Log.v(TAG, "deleteAwareNetworkInterface: transactionId=" + transactionId + ", "
                    + "interfaceName=" + interfaceName);
        }

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "deleteAwareNetworkInterface: null interface");
            return false;
        }

        try {
            WifiStatus status = iface.deleteDataInterfaceRequest(transactionId, interfaceName);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "deleteAwareNetworkInterface: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "deleteAwareNetworkInterface: exception: " + e);
            return false;
        }
    }

    /**
     * Initiates setting up a data-path between device and peer. Security is provided by either
     * PMK or Passphrase (not both) - if both are null then an open (unencrypted) link is set up.
     *
     * @param transactionId      Transaction ID for the transaction - used in the async callback to
     *                           match with the original request.
     * @param peerId             ID of the peer ID to associate the data path with. A value of 0
     *                           indicates that not associated with an existing session.
     * @param channelRequestType Indicates whether the specified channel is available, if available
     *                           requested or forced (resulting in failure if cannot be
     *                           accommodated).
     * @param channel            The channel on which to set up the data-path.
     * @param peer               The MAC address of the peer to create a connection with.
     * @param interfaceName      The interface on which to create the data connection.
     * @param pmk Pairwise master key (PMK - see IEEE 802.11i) for the data-path.
     * @param passphrase  Passphrase for the data-path.
     * @param capabilities The capabilities of the firmware.
     */
    public boolean initiateDataPath(short transactionId, int peerId, int channelRequestType,
            int channel, byte[] peer, String interfaceName, byte[] pmk, String passphrase,
            boolean isOutOfBand, Capabilities capabilities) {
        if (VDBG) {
            Log.v(TAG, "initiateDataPath: transactionId=" + transactionId + ", peerId=" + peerId
                    + ", channelRequestType=" + channelRequestType + ", channel=" + channel
                    + ", peer=" + String.valueOf(HexEncoding.encode(peer)) + ", interfaceName="
                    + interfaceName);
        }

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "initiateDataPath: null interface");
            return false;
        }

        if (capabilities == null) {
            Log.e(TAG, "initiateDataPath: null capabilities");
            return false;
        }

        NanInitiateDataPathRequest req = new NanInitiateDataPathRequest();
        req.peerId = peerId;
        copyArray(peer, req.peerDiscMacAddr);
        req.channelRequestType = channelRequestType;
        req.channel = channel;
        req.ifaceName = interfaceName;
        req.securityConfig.securityType = NanDataPathSecurityType.OPEN;
        if (pmk != null && pmk.length != 0) {
            req.securityConfig.cipherType = getStrongestCipherSuiteType(
                    capabilities.supportedCipherSuites);
            req.securityConfig.securityType = NanDataPathSecurityType.PMK;
            copyArray(pmk, req.securityConfig.pmk);
        }
        if (passphrase != null && passphrase.length() != 0) {
            req.securityConfig.cipherType = getStrongestCipherSuiteType(
                    capabilities.supportedCipherSuites);
            req.securityConfig.securityType = NanDataPathSecurityType.PASSPHRASE;
            convertNativeByteArrayToArrayList(passphrase.getBytes(), req.securityConfig.passphrase);
        }

        if (req.securityConfig.securityType != NanDataPathSecurityType.OPEN && isOutOfBand) {
            convertNativeByteArrayToArrayList(
                    SERVICE_NAME_FOR_OOB_DATA_PATH.getBytes(StandardCharsets.UTF_8),
                    req.serviceNameOutOfBand);
        }

        try {
            WifiStatus status = iface.initiateDataPathRequest(transactionId, req);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "initiateDataPath: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "initiateDataPath: exception: " + e);
            return false;
        }
    }

    /**
     * Responds to a data request from a peer. Security is provided by either PMK or Passphrase (not
     * both) - if both are null then an open (unencrypted) link is set up.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param accept Accept (true) or reject (false) the original call.
     * @param ndpId The NDP (Aware data path) ID. Obtained from the request callback.
     * @param interfaceName The interface on which the data path will be setup. Obtained from the
     *                      request callback.
     * @param pmk Pairwise master key (PMK - see IEEE 802.11i) for the data-path.
     * @param passphrase  Passphrase for the data-path.
     * @param isOutOfBand Is the data-path out-of-band (i.e. without a corresponding Aware discovery
     *                    session).
     * @param capabilities The capabilities of the firmware.
     */
    public boolean respondToDataPathRequest(short transactionId, boolean accept, int ndpId,
            String interfaceName, byte[] pmk, String passphrase, boolean isOutOfBand,
            Capabilities capabilities) {
        if (VDBG) {
            Log.v(TAG, "respondToDataPathRequest: transactionId=" + transactionId + ", accept="
                    + accept + ", int ndpId=" + ndpId + ", interfaceName=" + interfaceName);
        }

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "respondToDataPathRequest: null interface");
            return false;
        }

        if (capabilities == null) {
            Log.e(TAG, "initiateDataPath: null capabilities");
            return false;
        }

        NanRespondToDataPathIndicationRequest req = new NanRespondToDataPathIndicationRequest();
        req.acceptRequest = accept;
        req.ndpInstanceId = ndpId;
        req.ifaceName = interfaceName;
        req.securityConfig.securityType = NanDataPathSecurityType.OPEN;
        if (pmk != null && pmk.length != 0) {
            req.securityConfig.cipherType = getStrongestCipherSuiteType(
                    capabilities.supportedCipherSuites);
            req.securityConfig.securityType = NanDataPathSecurityType.PMK;
            copyArray(pmk, req.securityConfig.pmk);
        }
        if (passphrase != null && passphrase.length() != 0) {
            req.securityConfig.cipherType = getStrongestCipherSuiteType(
                    capabilities.supportedCipherSuites);
            req.securityConfig.securityType = NanDataPathSecurityType.PASSPHRASE;
            convertNativeByteArrayToArrayList(passphrase.getBytes(), req.securityConfig.passphrase);
        }

        if (req.securityConfig.securityType != NanDataPathSecurityType.OPEN && isOutOfBand) {
            convertNativeByteArrayToArrayList(
                    SERVICE_NAME_FOR_OOB_DATA_PATH.getBytes(StandardCharsets.UTF_8),
                    req.serviceNameOutOfBand);
        }

        try {
            WifiStatus status = iface.respondToDataPathIndicationRequest(transactionId, req);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "respondToDataPathRequest: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "respondToDataPathRequest: exception: " + e);
            return false;
        }
    }

    /**
     * Terminate an existing data-path (does not delete the interface).
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param ndpId The NDP (Aware data path) ID to be terminated.
     */
    public boolean endDataPath(short transactionId, int ndpId) {
        if (VDBG) {
            Log.v(TAG, "endDataPath: transactionId=" + transactionId + ", ndpId=" + ndpId);
        }

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "endDataPath: null interface");
            return false;
        }

        try {
            WifiStatus status = iface.terminateDataPathRequest(transactionId, ndpId);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "endDataPath: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "endDataPath: exception: " + e);
            return false;
        }
    }


    // utilities

    /**
     * Update the NAN configuration to reflect the current power settings.
     */
    private void updateConfigForPowerSettings(NanConfigRequest req, boolean isInteractive,
            boolean isIdle) {
        if (isIdle) { // lowest power state: doze
            updateSingleConfigForPowerSettings(req.bandSpecificConfig[NanBandIndex.NAN_BAND_5GHZ],
                    mSettableParameters.get(PARAM_DW_ON_IDLE_5GHZ));
            updateSingleConfigForPowerSettings(req.bandSpecificConfig[NanBandIndex.NAN_BAND_24GHZ],
                    mSettableParameters.get(PARAM_DW_ON_IDLE_24GHZ));
        } else if (!isInteractive) { // intermediate power state: inactive
            updateSingleConfigForPowerSettings(req.bandSpecificConfig[NanBandIndex.NAN_BAND_5GHZ],
                    mSettableParameters.get(PARAM_DW_ON_INACTIVE_5GHZ));
            updateSingleConfigForPowerSettings(req.bandSpecificConfig[NanBandIndex.NAN_BAND_24GHZ],
                    mSettableParameters.get(PARAM_DW_ON_INACTIVE_24GHZ));
        } else { // the default state
            updateSingleConfigForPowerSettings(req.bandSpecificConfig[NanBandIndex.NAN_BAND_5GHZ],
                    mSettableParameters.get(PARAM_DW_DEFAULT_5GHZ));
            updateSingleConfigForPowerSettings(req.bandSpecificConfig[NanBandIndex.NAN_BAND_24GHZ],
                    mSettableParameters.get(PARAM_DW_DEFAULT_24GHZ));
        }

        // else do nothing - normal power state
    }

    private void updateSingleConfigForPowerSettings(NanBandSpecificConfig cfg, int override) {
        if (override != -1) {
            cfg.validDiscoveryWindowIntervalVal = true;
            cfg.discoveryWindowIntervalVal = (byte) override;
        }
    }

    /**
     * Returns the strongest supported cipher suite.
     *
     * Baseline is very simple: 256 > 128 > 0.
     */
    private int getStrongestCipherSuiteType(int supportedCipherSuites) {
        if ((supportedCipherSuites & NanCipherSuiteType.SHARED_KEY_256_MASK) != 0) {
            return NanCipherSuiteType.SHARED_KEY_256_MASK;
        }
        if ((supportedCipherSuites & NanCipherSuiteType.SHARED_KEY_128_MASK) != 0) {
            return NanCipherSuiteType.SHARED_KEY_128_MASK;
        }
        return NanCipherSuiteType.NONE;
    }

    /**
     * Converts a byte[] to an ArrayList<Byte>. Fills in the entries of the 'to' array if
     * provided (non-null), otherwise creates and returns a new ArrayList<>.
     *
     * @param from The input byte[] to convert from.
     * @param to An optional ArrayList<> to fill in from 'from'.
     *
     * @return A newly allocated ArrayList<> if 'to' is null, otherwise null.
     */
    private ArrayList<Byte> convertNativeByteArrayToArrayList(byte[] from, ArrayList<Byte> to) {
        if (from == null) {
            from = new byte[0];
        }

        if (to == null) {
            to = new ArrayList<>(from.length);
        } else {
            to.ensureCapacity(from.length);
        }
        for (int i = 0; i < from.length; ++i) {
            to.add(from[i]);
        }
        return to;
    }

    private void copyArray(byte[] from, byte[] to) {
        if (from == null || to == null || from.length != to.length) {
            Log.e(TAG, "copyArray error: from=" + from + ", to=" + to);
            return;
        }
        for (int i = 0; i < from.length; ++i) {
            to[i] = from[i];
        }
    }

    private static String statusString(WifiStatus status) {
        if (status == null) {
            return "status=null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(status.code).append(" (").append(status.description).append(")");
        return sb.toString();
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("WifiAwareNativeApi:");
        pw.println("  mSettableParameters: " + mSettableParameters);
        mHal.dump(fd, pw, args);
    }
}
