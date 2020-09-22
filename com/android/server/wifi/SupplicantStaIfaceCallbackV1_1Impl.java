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
import android.hardware.wifi.supplicant.V1_0.ISupplicantStaIfaceCallback;
import android.net.wifi.WifiManager;

import java.util.ArrayList;

abstract class SupplicantStaIfaceCallbackV1_1Impl extends
        android.hardware.wifi.supplicant.V1_1.ISupplicantStaIfaceCallback.Stub {
    private static final String TAG = SupplicantStaIfaceCallbackV1_1Impl.class.getSimpleName();
    private final SupplicantStaIfaceHal mStaIfaceHal;
    private final String mIfaceName;
    private final Object mLock;
    private final WifiMonitor mWifiMonitor;
    private final SupplicantStaIfaceHal.SupplicantStaIfaceHalCallback mCallbackV10;

    SupplicantStaIfaceCallbackV1_1Impl(@NonNull SupplicantStaIfaceHal staIfaceHal,
            @NonNull String ifaceName,
            @NonNull Object lock,
            @NonNull WifiMonitor wifiMonitor) {
        mStaIfaceHal = staIfaceHal;
        mIfaceName = ifaceName;
        mLock = lock;
        mWifiMonitor = wifiMonitor;
        // Create an older callback for function delegation,
        // and it would cascadingly create older one.
        mCallbackV10 = mStaIfaceHal.new SupplicantStaIfaceHalCallback(mIfaceName);
    }

    @Override
    public void onNetworkAdded(int id) {
        mCallbackV10.onNetworkAdded(id);
    }

    @Override
    public void onNetworkRemoved(int id) {
        mCallbackV10.onNetworkRemoved(id);
    }

    @Override
    public void onStateChanged(int newState, byte[/* 6 */] bssid, int id,
                               ArrayList<Byte> ssid) {
        mCallbackV10.onStateChanged(newState, bssid, id, ssid);
    }

    @Override
    public void onAnqpQueryDone(byte[/* 6 */] bssid,
                                ISupplicantStaIfaceCallback.AnqpData data,
                                ISupplicantStaIfaceCallback.Hs20AnqpData hs20Data) {
        mCallbackV10.onAnqpQueryDone(bssid, data, hs20Data);
    }

    @Override
    public void onHs20IconQueryDone(byte[/* 6 */] bssid, String fileName,
                                    ArrayList<Byte> data) {
        mCallbackV10.onHs20IconQueryDone(bssid, fileName, data);
    }

    @Override
    public void onHs20SubscriptionRemediation(byte[/* 6 */] bssid,
                                              byte osuMethod, String url) {
        mCallbackV10.onHs20SubscriptionRemediation(bssid, osuMethod, url);
    }

    @Override
    public void onHs20DeauthImminentNotice(byte[/* 6 */] bssid, int reasonCode,
                                           int reAuthDelayInSec, String url) {
        mCallbackV10.onHs20DeauthImminentNotice(bssid, reasonCode, reAuthDelayInSec, url);
    }

    @Override
    public void onDisconnected(byte[/* 6 */] bssid, boolean locallyGenerated,
                               int reasonCode) {
        mCallbackV10.onDisconnected(bssid, locallyGenerated, reasonCode);
    }

    @Override
    public void onAssociationRejected(byte[/* 6 */] bssid, int statusCode,
                                      boolean timedOut) {
        mCallbackV10.onAssociationRejected(bssid, statusCode, timedOut);
    }

    @Override
    public void onAuthenticationTimeout(byte[/* 6 */] bssid) {
        mCallbackV10.onAuthenticationTimeout(bssid);
    }

    @Override
    public void onBssidChanged(byte reason, byte[/* 6 */] bssid) {
        mCallbackV10.onBssidChanged(reason, bssid);
    }

    @Override
    public void onEapFailure() {
        mCallbackV10.onEapFailure();
    }

    @Override
    public void onEapFailure_1_1(int code) {
        synchronized (mLock) {
            mStaIfaceHal.logCallback("onEapFailure_1_1");
            mWifiMonitor.broadcastAuthenticationFailureEvent(
                    mIfaceName, WifiManager.ERROR_AUTH_FAILURE_EAP_FAILURE, code);
        }
    }

    @Override
    public void onWpsEventSuccess() {
        mCallbackV10.onWpsEventSuccess();
    }

    @Override
    public void onWpsEventFail(byte[/* 6 */] bssid, short configError, short errorInd) {
        mCallbackV10.onWpsEventFail(bssid, configError, errorInd);
    }

    @Override
    public void onWpsEventPbcOverlap() {
        mCallbackV10.onWpsEventPbcOverlap();
    }

    @Override
    public void onExtRadioWorkStart(int id) {
        mCallbackV10.onExtRadioWorkStart(id);
    }

    @Override
    public void onExtRadioWorkTimeout(int id) {
        mCallbackV10.onExtRadioWorkTimeout(id);
    }
}
