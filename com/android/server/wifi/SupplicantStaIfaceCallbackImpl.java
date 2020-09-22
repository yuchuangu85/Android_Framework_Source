/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.ANQP3GPPNetwork;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.ANQPDomName;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.ANQPIPAddrAvailability;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.ANQPNAIRealm;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.ANQPRoamingConsortium;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.ANQPVenueName;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.HSConnCapability;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.HSFriendlyName;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.HSOSUProviders;
import static com.android.server.wifi.hotspot2.anqp.Constants.ANQPElementType.HSWANMetrics;

import android.annotation.NonNull;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIfaceCallback;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.util.Log;

import com.android.server.wifi.hotspot2.AnqpEvent;
import com.android.server.wifi.hotspot2.IconEvent;
import com.android.server.wifi.hotspot2.WnmData;
import com.android.server.wifi.hotspot2.anqp.ANQPElement;
import com.android.server.wifi.hotspot2.anqp.ANQPParser;
import com.android.server.wifi.hotspot2.anqp.Constants;
import com.android.server.wifi.util.NativeUtil;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

abstract class SupplicantStaIfaceCallbackImpl extends ISupplicantStaIfaceCallback.Stub {
    private static final String TAG = SupplicantStaIfaceCallbackImpl.class.getSimpleName();
    private final SupplicantStaIfaceHal mStaIfaceHal;
    private final String mIfaceName;
    private final Object mLock;
    private final WifiMonitor mWifiMonitor;
    private boolean mStateIsFourway = false; // Used to help check for PSK password mismatch

    SupplicantStaIfaceCallbackImpl(@NonNull SupplicantStaIfaceHal staIfaceHal,
            @NonNull String ifaceName,
            @NonNull Object lock,
            @NonNull WifiMonitor wifiMonitor) {
        mStaIfaceHal = staIfaceHal;
        mIfaceName = ifaceName;
        mLock = lock;
        mWifiMonitor = wifiMonitor;
    }

    /**
     * Converts the supplicant state received from HIDL to the equivalent framework state.
     */
    protected static SupplicantState supplicantHidlStateToFrameworkState(int state) {
        switch (state) {
            case ISupplicantStaIfaceCallback.State.DISCONNECTED:
                return SupplicantState.DISCONNECTED;
            case ISupplicantStaIfaceCallback.State.IFACE_DISABLED:
                return SupplicantState.INTERFACE_DISABLED;
            case ISupplicantStaIfaceCallback.State.INACTIVE:
                return SupplicantState.INACTIVE;
            case ISupplicantStaIfaceCallback.State.SCANNING:
                return SupplicantState.SCANNING;
            case ISupplicantStaIfaceCallback.State.AUTHENTICATING:
                return SupplicantState.AUTHENTICATING;
            case ISupplicantStaIfaceCallback.State.ASSOCIATING:
                return SupplicantState.ASSOCIATING;
            case ISupplicantStaIfaceCallback.State.ASSOCIATED:
                return SupplicantState.ASSOCIATED;
            case ISupplicantStaIfaceCallback.State.FOURWAY_HANDSHAKE:
                return SupplicantState.FOUR_WAY_HANDSHAKE;
            case ISupplicantStaIfaceCallback.State.GROUP_HANDSHAKE:
                return SupplicantState.GROUP_HANDSHAKE;
            case ISupplicantStaIfaceCallback.State.COMPLETED:
                return SupplicantState.COMPLETED;
            default:
                throw new IllegalArgumentException("Invalid state: " + state);
        }
    }


    /**
     * Parses the provided payload into an ANQP element.
     *
     * @param infoID  Element type.
     * @param payload Raw payload bytes.
     * @return AnqpElement instance on success, null on failure.
     */
    private ANQPElement parseAnqpElement(Constants.ANQPElementType infoID,
                                         ArrayList<Byte> payload) {
        synchronized (mLock) {
            try {
                return Constants.getANQPElementID(infoID) != null
                        ? ANQPParser.parseElement(
                        infoID, ByteBuffer.wrap(NativeUtil.byteArrayFromArrayList(payload)))
                        : ANQPParser.parseHS20Element(
                        infoID, ByteBuffer.wrap(NativeUtil.byteArrayFromArrayList(payload)));
            } catch (IOException | BufferUnderflowException e) {
                Log.e(TAG, "Failed parsing ANQP element payload: " + infoID, e);
                return null;
            }
        }
    }

    /**
     * Parse the ANQP element data and add to the provided elements map if successful.
     *
     * @param elementsMap Map to add the parsed out element to.
     * @param infoID  Element type.
     * @param payload Raw payload bytes.
     */
    private void addAnqpElementToMap(Map<Constants.ANQPElementType, ANQPElement> elementsMap,
                                     Constants.ANQPElementType infoID,
                                     ArrayList<Byte> payload) {
        synchronized (mLock) {
            if (payload == null || payload.isEmpty()) return;
            ANQPElement element = parseAnqpElement(infoID, payload);
            if (element != null) {
                elementsMap.put(infoID, element);
            }
        }
    }

    @Override
    public void onNetworkAdded(int id) {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onNetworkAdded");
        }
    }

    @Override
    public void onNetworkRemoved(int id) {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onNetworkRemoved");
            // Reset 4way handshake state since network has been removed.
            mStateIsFourway = false;
        }
    }

    @Override
    public void onStateChanged(int newState, byte[/* 6 */] bssid, int id,
                               ArrayList<Byte> ssid) {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onStateChanged");
            SupplicantState newSupplicantState =
                    supplicantHidlStateToFrameworkState(newState);
            WifiSsid wifiSsid =
                    WifiSsid.createFromByteArray(NativeUtil.byteArrayFromArrayList(ssid));
            String bssidStr = NativeUtil.macAddressFromByteArray(bssid);
            mStateIsFourway = (newState == ISupplicantStaIfaceCallback.State.FOURWAY_HANDSHAKE);
            if (newSupplicantState == SupplicantState.COMPLETED) {
                mWifiMonitor.broadcastNetworkConnectionEvent(
                        mIfaceName, mStaIfaceHal.getCurrentNetworkId(mIfaceName), bssidStr);
            }
            mWifiMonitor.broadcastSupplicantStateChangeEvent(
                    mIfaceName, mStaIfaceHal.getCurrentNetworkId(mIfaceName), wifiSsid,
                    bssidStr, newSupplicantState);
        }
    }

    @Override
    public void onAnqpQueryDone(byte[/* 6 */] bssid,
                                ISupplicantStaIfaceCallback.AnqpData data,
                                ISupplicantStaIfaceCallback.Hs20AnqpData hs20Data) {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onAnqpQueryDone");
            Map<Constants.ANQPElementType, ANQPElement> elementsMap = new HashMap<>();
            addAnqpElementToMap(elementsMap, ANQPVenueName, data.venueName);
            addAnqpElementToMap(elementsMap, ANQPRoamingConsortium, data.roamingConsortium);
            addAnqpElementToMap(
                    elementsMap, ANQPIPAddrAvailability, data.ipAddrTypeAvailability);
            addAnqpElementToMap(elementsMap, ANQPNAIRealm, data.naiRealm);
            addAnqpElementToMap(elementsMap, ANQP3GPPNetwork, data.anqp3gppCellularNetwork);
            addAnqpElementToMap(elementsMap, ANQPDomName, data.domainName);
            addAnqpElementToMap(elementsMap, HSFriendlyName, hs20Data.operatorFriendlyName);
            addAnqpElementToMap(elementsMap, HSWANMetrics, hs20Data.wanMetrics);
            addAnqpElementToMap(elementsMap, HSConnCapability, hs20Data.connectionCapability);
            addAnqpElementToMap(elementsMap, HSOSUProviders, hs20Data.osuProvidersList);
            mWifiMonitor.broadcastAnqpDoneEvent(
                    mIfaceName, new AnqpEvent(NativeUtil.macAddressToLong(bssid), elementsMap));
        }
    }

    @Override
    public void onHs20IconQueryDone(byte[/* 6 */] bssid, String fileName,
                                    ArrayList<Byte> data) {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onHs20IconQueryDone");
            mWifiMonitor.broadcastIconDoneEvent(
                    mIfaceName,
                    new IconEvent(NativeUtil.macAddressToLong(bssid), fileName, data.size(),
                            NativeUtil.byteArrayFromArrayList(data)));
        }
    }

    @Override
    public void onHs20SubscriptionRemediation(byte[/* 6 */] bssid, byte osuMethod, String url) {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onHs20SubscriptionRemediation");
            mWifiMonitor.broadcastWnmEvent(
                    mIfaceName,
                    new WnmData(NativeUtil.macAddressToLong(bssid), url, osuMethod));
        }
    }

    @Override
    public void onHs20DeauthImminentNotice(byte[/* 6 */] bssid, int reasonCode,
                                           int reAuthDelayInSec, String url) {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onHs20DeauthImminentNotice");
            mWifiMonitor.broadcastWnmEvent(
                    mIfaceName,
                    new WnmData(NativeUtil.macAddressToLong(bssid), url,
                            reasonCode == WnmData.ESS, reAuthDelayInSec));
        }
    }

    @Override
    public void onDisconnected(byte[/* 6 */] bssid, boolean locallyGenerated, int reasonCode) {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onDisconnected");
            if (mStaIfaceHal.isVerboseLoggingEnabled()) {
                Log.e(TAG, "onDisconnected 4way=" + mStateIsFourway
                        + " locallyGenerated=" + locallyGenerated
                        + " reasonCode=" + reasonCode);
            }
            if (mStateIsFourway
                    && (!locallyGenerated || reasonCode != ReasonCode.IE_IN_4WAY_DIFFERS)) {
                mWifiMonitor.broadcastAuthenticationFailureEvent(
                        mIfaceName, WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD, -1);
            }
            mWifiMonitor.broadcastNetworkDisconnectionEvent(
                    mIfaceName, locallyGenerated ? 1 : 0, reasonCode,
                    NativeUtil.macAddressFromByteArray(bssid));
        }
    }

    @Override
    public void onAssociationRejected(byte[/* 6 */] bssid, int statusCode, boolean timedOut) {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onAssociationRejected");
            boolean isWrongPwd = false;
            WifiConfiguration curConfiguration =
                    mStaIfaceHal.getCurrentNetworkLocalConfig(mIfaceName);

            if (curConfiguration != null) {
                if (!timedOut) {
                    Log.d(TAG, "flush PMK cache due to association rejection for config id "
                            + curConfiguration.networkId + ".");
                    mStaIfaceHal.removePmkCacheEntry(curConfiguration.networkId);
                }
                // Special handling for WPA3-Personal networks. If the password is
                // incorrect, the AP will send association rejection, with status code 1
                // (unspecified failure). In SAE networks, the password authentication
                // is not related to the 4-way handshake. In this case, we will send an
                // authentication failure event up.
                if (statusCode == StatusCode.UNSPECIFIED_FAILURE
                        && WifiConfigurationUtil.isConfigForSaeNetwork(curConfiguration)) {
                    mStaIfaceHal.logCallback("SAE incorrect password");
                    isWrongPwd = true;
                } else if (statusCode == StatusCode.CHALLENGE_FAIL
                        && WifiConfigurationUtil.isConfigForWepNetwork(curConfiguration)) {
                    mStaIfaceHal.logCallback("WEP incorrect password");
                    isWrongPwd = true;
                }
            }

            if (isWrongPwd) {
                mWifiMonitor.broadcastAuthenticationFailureEvent(
                        mIfaceName, WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD, -1);
            }
            mWifiMonitor
                    .broadcastAssociationRejectionEvent(
                            mIfaceName, statusCode, timedOut,
                            NativeUtil.macAddressFromByteArray(bssid));
        }
    }

    @Override
    public void onAuthenticationTimeout(byte[/* 6 */] bssid) {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onAuthenticationTimeout");
            mWifiMonitor.broadcastAuthenticationFailureEvent(
                    mIfaceName, WifiManager.ERROR_AUTH_FAILURE_TIMEOUT, -1);
        }
    }

    @Override
    public void onBssidChanged(byte reason, byte[/* 6 */] bssid) {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onBssidChanged");
            if (reason == BssidChangeReason.ASSOC_START) {
                mWifiMonitor.broadcastTargetBssidEvent(
                        mIfaceName, NativeUtil.macAddressFromByteArray(bssid));
            } else if (reason == BssidChangeReason.ASSOC_COMPLETE) {
                mWifiMonitor.broadcastAssociatedBssidEvent(
                        mIfaceName, NativeUtil.macAddressFromByteArray(bssid));
            }
        }
    }

    @Override
    public void onEapFailure() {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onEapFailure");
            mWifiMonitor.broadcastAuthenticationFailureEvent(
                    mIfaceName, WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE, -1);
        }
    }

    @Override
    public void onWpsEventSuccess() {
        mStaIfaceHal.logCallback("onWpsEventSuccess");
        synchronized (mLock) {
            mWifiMonitor.broadcastWpsSuccessEvent(mIfaceName);
        }
    }

    @Override
    public void onWpsEventFail(byte[/* 6 */] bssid, short configError, short errorInd) {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onWpsEventFail");
            if (configError == WpsConfigError.MSG_TIMEOUT
                    && errorInd == WpsErrorIndication.NO_ERROR) {
                mWifiMonitor.broadcastWpsTimeoutEvent(mIfaceName);
            } else {
                mWifiMonitor.broadcastWpsFailEvent(mIfaceName, configError, errorInd);
            }
        }
    }

    @Override
    public void onWpsEventPbcOverlap() {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onWpsEventPbcOverlap");
            mWifiMonitor.broadcastWpsOverlapEvent(mIfaceName);
        }
    }

    @Override
    public void onExtRadioWorkStart(int id) {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onExtRadioWorkStart");
        }
    }

    @Override
    public void onExtRadioWorkTimeout(int id) {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onExtRadioWorkTimeout");
        }
    }
}
