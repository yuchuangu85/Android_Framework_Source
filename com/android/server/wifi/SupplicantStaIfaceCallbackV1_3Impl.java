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

import static com.android.server.wifi.SupplicantStaIfaceCallbackImpl.supplicantHidlStateToFrameworkState;

import android.annotation.NonNull;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIfaceCallback;
import android.hardware.wifi.supplicant.V1_3.ISupplicantStaIfaceCallback.BssTmData;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiSsid;
import android.util.Log;

import com.android.server.wifi.util.NativeUtil;

import java.util.ArrayList;

abstract class SupplicantStaIfaceCallbackV1_3Impl extends
        android.hardware.wifi.supplicant.V1_3.ISupplicantStaIfaceCallback.Stub {
    private static final String TAG = SupplicantStaIfaceCallbackV1_3Impl.class.getSimpleName();
    private final SupplicantStaIfaceHal mStaIfaceHal;
    private final String mIfaceName;
    private final WifiMonitor mWifiMonitor;
    private final SupplicantStaIfaceHal.SupplicantStaIfaceHalCallbackV1_2 mCallbackV12;
    private boolean mStateIsFourwayV13 = false; // Used to help check for PSK password mismatch

    SupplicantStaIfaceCallbackV1_3Impl(@NonNull SupplicantStaIfaceHal staIfaceHal,
            @NonNull String ifaceName,
            @NonNull WifiMonitor wifiMonitor) {
        mStaIfaceHal = staIfaceHal;
        mIfaceName = ifaceName;
        mWifiMonitor = wifiMonitor;
        // Create an older callback for function delegation,
        // and it would cascadingly create older one.
        mCallbackV12 = mStaIfaceHal.new SupplicantStaIfaceHalCallbackV1_2(mIfaceName);
    }

    @Override
    public void onNetworkAdded(int id) {
        mCallbackV12.onNetworkAdded(id);
    }

    @Override
    public void onNetworkRemoved(int id) {
        mStaIfaceHal.logCallback("onNetworkRemoved");
        // Reset 4way handshake state since network has been removed.
        mStateIsFourwayV13 = false;
    }

    @Override
    public void onStateChanged(int newState, byte[/* 6 */] bssid, int id,
            ArrayList<Byte> ssid) {
        mCallbackV12.onStateChanged(newState, bssid, id, ssid);
    }

    @Override
    public void onAnqpQueryDone(byte[/* 6 */] bssid,
            ISupplicantStaIfaceCallback.AnqpData data,
            ISupplicantStaIfaceCallback.Hs20AnqpData hs20Data) {
        mCallbackV12.onAnqpQueryDone(bssid, data, hs20Data);
    }

    @Override
    public void onHs20IconQueryDone(byte[/* 6 */] bssid, String fileName,
            ArrayList<Byte> data) {
        mCallbackV12.onHs20IconQueryDone(bssid, fileName, data);
    }

    @Override
    public void onHs20SubscriptionRemediation(byte[/* 6 */] bssid,
            byte osuMethod, String url) {
        mCallbackV12.onHs20SubscriptionRemediation(bssid, osuMethod, url);
    }

    @Override
    public void onHs20DeauthImminentNotice(byte[/* 6 */] bssid, int reasonCode,
            int reAuthDelayInSec, String url) {
        mCallbackV12.onHs20DeauthImminentNotice(bssid, reasonCode, reAuthDelayInSec, url);
    }

    @Override
    public void onDisconnected(byte[/* 6 */] bssid, boolean locallyGenerated,
            int reasonCode) {
        mStaIfaceHal.logCallback("onDisconnected");
        if (mStaIfaceHal.isVerboseLoggingEnabled()) {
            Log.e(TAG, "onDisconnected 4way=" + mStateIsFourwayV13
                    + " locallyGenerated=" + locallyGenerated
                    + " reasonCode=" + reasonCode);
        }
        if (mStateIsFourwayV13
                && (!locallyGenerated || reasonCode != ReasonCode.IE_IN_4WAY_DIFFERS)) {
            mWifiMonitor.broadcastAuthenticationFailureEvent(
                    mIfaceName, WifiManager.ERROR_AUTH_FAILURE_WRONG_PSWD, -1);
        }
        mWifiMonitor.broadcastNetworkDisconnectionEvent(
                mIfaceName, locallyGenerated ? 1 : 0, reasonCode,
                NativeUtil.macAddressFromByteArray(bssid));
    }

    @Override
    public void onAssociationRejected(byte[/* 6 */] bssid, int statusCode,
            boolean timedOut) {
        mCallbackV12.onAssociationRejected(bssid, statusCode, timedOut);
    }

    @Override
    public void onAuthenticationTimeout(byte[/* 6 */] bssid) {
        mCallbackV12.onAuthenticationTimeout(bssid);
    }

    @Override
    public void onBssidChanged(byte reason, byte[/* 6 */] bssid) {
        mCallbackV12.onBssidChanged(reason, bssid);
    }

    @Override
    public void onEapFailure() {
        mCallbackV12.onEapFailure();
    }

    @Override
    public void onEapFailure_1_1(int code) {
        mCallbackV12.onEapFailure_1_1(code);
    }

    @Override
    public void onEapFailure_1_3(int code) {
        mWifiMonitor.broadcastAuthenticationFailureEvent(
                mIfaceName, WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE, code);
    }

    @Override
    public void onWpsEventSuccess() {
        mCallbackV12.onWpsEventSuccess();
    }

    @Override
    public void onWpsEventFail(byte[/* 6 */] bssid, short configError, short errorInd) {
        mCallbackV12.onWpsEventFail(bssid, configError, errorInd);
    }

    @Override
    public void onWpsEventPbcOverlap() {
        mCallbackV12.onWpsEventPbcOverlap();
    }

    @Override
    public void onExtRadioWorkStart(int id) {
        mCallbackV12.onExtRadioWorkStart(id);
    }

    @Override
    public void onExtRadioWorkTimeout(int id) {
        mCallbackV12.onExtRadioWorkTimeout(id);
    }

    @Override
    public void onDppSuccessConfigReceived(ArrayList<Byte> ssid, String password,
            byte[] psk, int securityAkm) {
        mCallbackV12.onDppSuccessConfigReceived(
                ssid, password, psk, securityAkm);
    }

    @Override
    public void onDppSuccessConfigSent() {
        mCallbackV12.onDppSuccessConfigSent();
    }

    @Override
    public void onDppProgress(int code) {
        mCallbackV12.onDppProgress(code);
    }

    @Override
    public void onDppFailure(int code) {
        mCallbackV12.onDppFailure(code);
    }

    @Override
    public void onPmkCacheAdded(long expirationTimeInSec, ArrayList<Byte> serializedEntry) {
        WifiConfiguration curConfig = mStaIfaceHal.getCurrentNetworkLocalConfig(mIfaceName);

        if (curConfig == null) return;

        if (WifiConfigurationUtil.isConfigForPskNetwork(curConfig)) return;

        mStaIfaceHal.addPmkCacheEntry(mIfaceName,
                curConfig.networkId, expirationTimeInSec, serializedEntry);
        mStaIfaceHal.logCallback(
                "onPmkCacheAdded: update pmk cache for config id "
                + curConfig.networkId
                + " on "
                + mIfaceName);
    }

    @Override
    public void onDppProgress_1_3(int code) {
        if (mStaIfaceHal.getDppCallback() != null) {
            mStaIfaceHal.getDppCallback().onProgress(code);
        } else {
            Log.e(TAG, "onDppProgress callback is null");
        }
    }

    @Override
    public void onDppFailure_1_3(int code, String ssid, String channelList,
            ArrayList<Short> bandList) {
        if (mStaIfaceHal.getDppCallback() != null) {
            int[] bandListArray = null;

            // Convert operating class list to a primitive array
            if (bandList != null) {
                bandListArray = new int[bandList.size()];

                for (int i = 0; i < bandList.size(); i++) {
                    bandListArray[i] = bandList.get(i).intValue();
                }
            }
            mStaIfaceHal.getDppCallback().onFailure(code, ssid, channelList, bandListArray);
        } else {
            Log.e(TAG, "onDppFailure callback is null");
        }
    }

    @Override
    public void onDppSuccess(int code) {
        if (mStaIfaceHal.getDppCallback() != null) {
            mStaIfaceHal.getDppCallback().onSuccess(code);
        } else {
            Log.e(TAG, "onDppFailure callback is null");
        }
    }

    @Override
    public void onBssTmHandlingDone(BssTmData tmData) {
        MboOceController.BtmFrameData btmFrmData = new MboOceController.BtmFrameData();

        btmFrmData.mStatus = halToFrameworkBtmResponseStatus(tmData.status);
        btmFrmData.mBssTmDataFlagsMask = halToFrameworkBssTmDataFlagsMask(tmData.flags);
        btmFrmData.mBlackListDurationMs = tmData.assocRetryDelayMs;
        if ((tmData.flags & BssTmDataFlagsMask.MBO_TRANSITION_REASON_CODE_INCLUDED) != 0) {
            btmFrmData.mTransitionReason = halToFrameworkMboTransitionReason(
                    tmData.mboTransitionReason);
        }
        if ((tmData.flags
                & BssTmDataFlagsMask.MBO_CELLULAR_DATA_CONNECTION_PREFERENCE_INCLUDED) != 0) {
            btmFrmData.mCellPreference =
                    halToFrameworkMboCellularDataConnectionPreference(tmData.mboCellPreference);
        }
        mStaIfaceHal.logCallback(
                "onBssTmHandlingDone: Handle BTM handling event");
        mWifiMonitor.broadcastBssTmHandlingDoneEvent(mIfaceName, btmFrmData);
    }

    private @MboOceConstants.BtmResponseStatus int halToFrameworkBtmResponseStatus(int status) {
        switch (status) {
            case BssTmStatusCode.ACCEPT:
                return MboOceConstants.BTM_RESPONSE_STATUS_ACCEPT;
            case BssTmStatusCode.REJECT_UNSPECIFIED:
                return MboOceConstants.BTM_RESPONSE_STATUS_REJECT_UNSPECIFIED;
            case BssTmStatusCode.REJECT_INSUFFICIENT_BEACON:
                return MboOceConstants.BTM_RESPONSE_STATUS_REJECT_INSUFFICIENT_BEACON;
            case BssTmStatusCode.REJECT_INSUFFICIENT_CAPABITY:
                return MboOceConstants.BTM_RESPONSE_STATUS_REJECT_INSUFFICIENT_CAPABITY;
            case BssTmStatusCode.REJECT_BSS_TERMINATION_UNDESIRED:
                return MboOceConstants.BTM_RESPONSE_STATUS_REJECT_BSS_TERMINATION_UNDESIRED;
            case BssTmStatusCode.REJECT_BSS_TERMINATION_DELAY_REQUEST:
                return MboOceConstants.BTM_RESPONSE_STATUS_REJECT_BSS_TERMINATION_DELAY_REQUEST;
            case BssTmStatusCode.REJECT_STA_CANDIDATE_LIST_PROVIDED:
                return MboOceConstants.BTM_RESPONSE_STATUS_REJECT_STA_CANDIDATE_LIST_PROVIDED;
            case BssTmStatusCode.REJECT_NO_SUITABLE_CANDIDATES:
                return MboOceConstants.BTM_RESPONSE_STATUS_REJECT_NO_SUITABLE_CANDIDATES;
            case BssTmStatusCode.REJECT_LEAVING_ESS:
                return MboOceConstants.BTM_RESPONSE_STATUS_REJECT_LEAVING_ESS;
            default:
                return MboOceConstants.BTM_RESPONSE_STATUS_REJECT_RESERVED;
        }
    }

    private int halToFrameworkBssTmDataFlagsMask(int flags) {
        int tmDataflags = 0;
        if ((flags & BssTmDataFlagsMask.WNM_MODE_PREFERRED_CANDIDATE_LIST_INCLUDED) != 0) {
            tmDataflags |= MboOceConstants.BTM_DATA_FLAG_PREFERRED_CANDIDATE_LIST_INCLUDED;
        }
        if ((flags & BssTmDataFlagsMask.WNM_MODE_ABRIDGED) != 0) {
            tmDataflags |= MboOceConstants.BTM_DATA_FLAG_MODE_ABRIDGED;
        }
        if ((flags & BssTmDataFlagsMask.WNM_MODE_DISASSOCIATION_IMMINENT) != 0) {
            tmDataflags |= MboOceConstants.BTM_DATA_FLAG_DISASSOCIATION_IMMINENT;
        }
        if ((flags & BssTmDataFlagsMask.WNM_MODE_BSS_TERMINATION_INCLUDED) != 0) {
            tmDataflags |= MboOceConstants.BTM_DATA_FLAG_BSS_TERMINATION_INCLUDED;
        }
        if ((flags & BssTmDataFlagsMask.WNM_MODE_ESS_DISASSOCIATION_IMMINENT) != 0) {
            tmDataflags |= MboOceConstants.BTM_DATA_FLAG_ESS_DISASSOCIATION_IMMINENT;
        }
        if ((flags & BssTmDataFlagsMask.MBO_TRANSITION_REASON_CODE_INCLUDED) != 0) {
            tmDataflags |= MboOceConstants.BTM_DATA_FLAG_MBO_TRANSITION_REASON_CODE_INCLUDED;
        }
        if ((flags & BssTmDataFlagsMask.MBO_ASSOC_RETRY_DELAY_INCLUDED) != 0) {
            tmDataflags |= MboOceConstants.BTM_DATA_FLAG_MBO_ASSOC_RETRY_DELAY_INCLUDED;
        }
        if ((flags & BssTmDataFlagsMask.MBO_CELLULAR_DATA_CONNECTION_PREFERENCE_INCLUDED) != 0) {
            tmDataflags |=
                    MboOceConstants.BTM_DATA_FLAG_MBO_CELL_DATA_CONNECTION_PREFERENCE_INCLUDED;
        }
        return tmDataflags;
    }

    private @MboOceConstants.MboTransitionReason int halToFrameworkMboTransitionReason(
            int reason) {
        switch (reason) {
            case MboTransitionReasonCode.UNSPECIFIED:
                return MboOceConstants.MBO_TRANSITION_REASON_UNSPECIFIED;
            case MboTransitionReasonCode.EXCESSIVE_FRAME_LOSS:
                return MboOceConstants.MBO_TRANSITION_REASON_EXCESSIVE_FRAME_LOSS;
            case MboTransitionReasonCode.EXCESSIVE_TRAFFIC_DELAY:
                return MboOceConstants.MBO_TRANSITION_REASON_EXCESSIVE_TRAFFIC_DELAY;
            case MboTransitionReasonCode.INSUFFICIENT_BANDWIDTH:
                return MboOceConstants.MBO_TRANSITION_REASON_INSUFFICIENT_BANDWIDTH;
            case MboTransitionReasonCode.LOAD_BALANCING:
                return MboOceConstants.MBO_TRANSITION_REASON_LOAD_BALANCING;
            case MboTransitionReasonCode.LOW_RSSI:
                return MboOceConstants.MBO_TRANSITION_REASON_LOW_RSSI;
            case MboTransitionReasonCode.RX_EXCESSIVE_RETRIES:
                return MboOceConstants.MBO_TRANSITION_REASON_RX_EXCESSIVE_RETRIES;
            case MboTransitionReasonCode.HIGH_INTERFERENCE:
                return MboOceConstants.MBO_TRANSITION_REASON_HIGH_INTERFERENCE;
            case MboTransitionReasonCode.GRAY_ZONE:
                return MboOceConstants.MBO_TRANSITION_REASON_GRAY_ZONE;
            default:
                return MboOceConstants.MBO_TRANSITION_REASON_RESERVED;
        }
    }

    private @MboOceConstants.MboTransitionReason int
            halToFrameworkMboCellularDataConnectionPreference(int cellPref) {
        switch (cellPref) {
            case MboCellularDataConnectionPrefValue.EXCLUDED:
                return MboOceConstants.MBO_CELLULAR_DATA_CONNECTION_EXCLUDED;
            case MboCellularDataConnectionPrefValue.NOT_PREFERRED:
                return MboOceConstants.MBO_CELLULAR_DATA_CONNECTION_NOT_PREFERRED;
            case MboCellularDataConnectionPrefValue.PREFERRED:
                return MboOceConstants.MBO_CELLULAR_DATA_CONNECTION_PREFERRED;
            default:
                return MboOceConstants.MBO_CELLULAR_DATA_CONNECTION_RESERVED;
        }
    }

    @Override
    public void onStateChanged_1_3(int newState, byte[/* 6 */] bssid, int id,
            ArrayList<Byte> ssid, boolean filsHlpSent) {
        mStaIfaceHal.logCallback("onStateChanged_1_3");
        SupplicantState newSupplicantState =
                supplicantHidlStateToFrameworkState(newState);
        WifiSsid wifiSsid =
                WifiSsid.createFromByteArray(NativeUtil.byteArrayFromArrayList(ssid));
        String bssidStr = NativeUtil.macAddressFromByteArray(bssid);
        mStateIsFourwayV13 =
                (newState == ISupplicantStaIfaceCallback.State.FOURWAY_HANDSHAKE);
        if (newSupplicantState == SupplicantState.COMPLETED) {
            if (filsHlpSent) {
                mWifiMonitor.broadcastFilsNetworkConnectionEvent(
                        mIfaceName, mStaIfaceHal.getCurrentNetworkId(mIfaceName), bssidStr);
            } else {
                mWifiMonitor.broadcastNetworkConnectionEvent(
                        mIfaceName, mStaIfaceHal.getCurrentNetworkId(mIfaceName), bssidStr);
            }
        }
        mWifiMonitor.broadcastSupplicantStateChangeEvent(
                mIfaceName, mStaIfaceHal.getCurrentNetworkId(mIfaceName),
                wifiSsid, bssidStr, newSupplicantState);
    }
}
