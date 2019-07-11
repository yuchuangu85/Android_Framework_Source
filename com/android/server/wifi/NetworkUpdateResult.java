/*
 * Copyright (C) 2010 The Android Open Source Project
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

import static android.net.wifi.WifiConfiguration.INVALID_NETWORK_ID;

public class NetworkUpdateResult {
    int netId;
    boolean ipChanged;
    boolean proxyChanged;
    boolean credentialChanged;
    boolean isNewNetwork = false;

    public NetworkUpdateResult(int id) {
        netId = id;
        ipChanged = false;
        proxyChanged = false;
        credentialChanged = false;
    }

    public NetworkUpdateResult(boolean ip, boolean proxy, boolean credential) {
        netId = INVALID_NETWORK_ID;
        ipChanged = ip;
        proxyChanged = proxy;
        credentialChanged = credential;
    }

    public void setNetworkId(int id) {
        netId = id;
    }

    public int getNetworkId() {
        return netId;
    }

    public boolean hasIpChanged() {
        return ipChanged;
    }

    public boolean hasProxyChanged() {
        return proxyChanged;
    }

    public boolean hasCredentialChanged() {
        return credentialChanged;
    }

    public boolean isNewNetwork() {
        return isNewNetwork;
    }

    public void setIsNewNetwork(boolean isNew) {
        isNewNetwork = isNew;
    }

    public boolean isSuccess() {
        return netId != INVALID_NETWORK_ID;
    }

}
