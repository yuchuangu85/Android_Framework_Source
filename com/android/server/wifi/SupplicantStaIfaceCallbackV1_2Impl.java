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

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIfaceCallback;
import android.hardware.wifi.supplicant.V1_2.DppAkm;
import android.hardware.wifi.supplicant.V1_2.DppFailureCode;
import android.hardware.wifi.supplicant.V1_3.DppSuccessCode;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiSsid;
import android.os.Process;
import android.util.Log;

import com.android.server.wifi.util.NativeUtil;

import java.util.ArrayList;

abstract class SupplicantStaIfaceCallbackV1_2Impl extends
        android.hardware.wifi.supplicant.V1_2.ISupplicantStaIfaceCallback.Stub {
    private static final String TAG = SupplicantStaIfaceCallbackV1_2Impl.class.getSimpleName();
    private final SupplicantStaIfaceHal mStaIfaceHal;
    private final String mIfaceName;
    private final Context mContext;
    private final SupplicantStaIfaceHal.SupplicantStaIfaceHalCallbackV1_1 mCallbackV11;

    SupplicantStaIfaceCallbackV1_2Impl(@NonNull SupplicantStaIfaceHal staIfaceHal,
            @NonNull String ifaceName,
            @NonNull Context context) {
        mStaIfaceHal = staIfaceHal;
        mIfaceName = ifaceName;
        mContext = context;
        // Create an older callback for function delegation,
        // and it would cascadingly create older one.
        mCallbackV11 = mStaIfaceHal.new SupplicantStaIfaceHalCallbackV1_1(mIfaceName);
    }

    @Override
    public void onNetworkAdded(int id) {
        mCallbackV11.onNetworkAdded(id);
    }

    @Override
    public void onNetworkRemoved(int id) {
        mCallbackV11.onNetworkRemoved(id);
    }

    @Override
    public void onStateChanged(int newState, byte[/* 6 */] bssid, int id,
            ArrayList<Byte> ssid) {
        mCallbackV11.onStateChanged(newState, bssid, id, ssid);
    }

    @Override
    public void onAnqpQueryDone(byte[/* 6 */] bssid,
            ISupplicantStaIfaceCallback.AnqpData data,
            ISupplicantStaIfaceCallback.Hs20AnqpData hs20Data) {
        mCallbackV11.onAnqpQueryDone(bssid, data, hs20Data);
    }

    @Override
    public void onHs20IconQueryDone(byte[/* 6 */] bssid, String fileName,
            ArrayList<Byte> data) {
        mCallbackV11.onHs20IconQueryDone(bssid, fileName, data);
    }

    @Override
    public void onHs20SubscriptionRemediation(byte[/* 6 */] bssid,
            byte osuMethod, String url) {
        mCallbackV11.onHs20SubscriptionRemediation(bssid, osuMethod, url);
    }

    @Override
    public void onHs20DeauthImminentNotice(byte[/* 6 */] bssid, int reasonCode,
            int reAuthDelayInSec, String url) {
        mCallbackV11.onHs20DeauthImminentNotice(bssid, reasonCode, reAuthDelayInSec, url);
    }

    @Override
    public void onDisconnected(byte[/* 6 */] bssid, boolean locallyGenerated,
            int reasonCode) {
        mCallbackV11.onDisconnected(bssid, locallyGenerated, reasonCode);
    }

    @Override
    public void onAssociationRejected(byte[/* 6 */] bssid, int statusCode,
            boolean timedOut) {
        mCallbackV11.onAssociationRejected(bssid, statusCode, timedOut);
    }

    @Override
    public void onAuthenticationTimeout(byte[/* 6 */] bssid) {
        mCallbackV11.onAuthenticationTimeout(bssid);
    }

    @Override
    public void onBssidChanged(byte reason, byte[/* 6 */] bssid) {
        mCallbackV11.onBssidChanged(reason, bssid);
    }

    @Override
    public void onEapFailure() {
        mCallbackV11.onEapFailure();
    }

    @Override
    public void onEapFailure_1_1(int code) {
        mCallbackV11.onEapFailure_1_1(code);
    }

    @Override
    public void onWpsEventSuccess() {
        mCallbackV11.onWpsEventSuccess();
    }

    @Override
    public void onWpsEventFail(byte[/* 6 */] bssid, short configError, short errorInd) {
        mCallbackV11.onWpsEventFail(bssid, configError, errorInd);
    }

    @Override
    public void onWpsEventPbcOverlap() {
        mCallbackV11.onWpsEventPbcOverlap();
    }

    @Override
    public void onExtRadioWorkStart(int id) {
        mCallbackV11.onExtRadioWorkStart(id);
    }

    @Override
    public void onExtRadioWorkTimeout(int id) {
        mCallbackV11.onExtRadioWorkTimeout(id);
    }

    @Override
    public void onDppSuccessConfigReceived(ArrayList<Byte> ssid, String password,
            byte[] psk, int securityAkm) {
        if (mStaIfaceHal.getDppCallback() == null) {
            Log.e(TAG, "onDppSuccessConfigReceived callback is null");
            return;
        }

        WifiConfiguration newWifiConfiguration = new WifiConfiguration();

        // Set up SSID
        WifiSsid wifiSsid =
                WifiSsid.createFromByteArray(NativeUtil.byteArrayFromArrayList(ssid));

        newWifiConfiguration.SSID = "\"" + wifiSsid.toString() + "\"";

        // Set up password or PSK
        if (password != null) {
            newWifiConfiguration.preSharedKey = "\"" + password + "\"";
        } else if (psk != null) {
            newWifiConfiguration.preSharedKey = psk.toString();
        }

        // Set up key management: SAE or PSK
        if (securityAkm == DppAkm.SAE || securityAkm == DppAkm.PSK_SAE) {
            newWifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE);
            newWifiConfiguration.requirePmf = true;
        } else if (securityAkm == DppAkm.PSK) {
            newWifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        } else {
            // No other AKMs are currently supported
            onDppFailure(DppFailureCode.NOT_SUPPORTED);
            return;
        }

        // Set up default values
        newWifiConfiguration.creatorName = mContext.getPackageManager()
                .getNameForUid(Process.WIFI_UID);
        newWifiConfiguration.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
        newWifiConfiguration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
        newWifiConfiguration.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
        newWifiConfiguration.status = WifiConfiguration.Status.ENABLED;

        mStaIfaceHal.getDppCallback().onSuccessConfigReceived(newWifiConfiguration);
    }

    @Override
    public void onDppSuccessConfigSent() {
        if (mStaIfaceHal.getDppCallback() != null) {
            mStaIfaceHal.getDppCallback().onSuccess(DppSuccessCode.CONFIGURATION_SENT);
        } else {
            Log.e(TAG, "onSuccessConfigSent callback is null");
        }
    }

    @Override
    public void onDppProgress(int code) {
        if (mStaIfaceHal.getDppCallback() != null) {
            mStaIfaceHal.getDppCallback().onProgress(code);
        } else {
            Log.e(TAG, "onDppProgress callback is null");
        }
    }

    @Override
    public void onDppFailure(int code) {
        if (mStaIfaceHal.getDppCallback() != null) {
            mStaIfaceHal.getDppCallback().onFailure(code, null, null, null);
        } else {
            Log.e(TAG, "onDppFailure callback is null");
        }
    }
}
