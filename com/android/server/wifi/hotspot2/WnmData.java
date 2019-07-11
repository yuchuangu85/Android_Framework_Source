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

package com.android.server.wifi.hotspot2;

/**
 * This class carries the payload of a Hotspot 2.0 Wireless Network Management (WNM) frame,
 * described in the Hotspot 2.0 spec, section 3.2.
 */
public class WnmData {
    public static final int ESS = 1;   // HS2.0 spec section 3.2.1.2, table 4

    private final long mBssid;
    private final String mUrl;
    private final boolean mDeauthEvent;
    private final int mMethod;
    private final boolean mEss;
    private final int mDelay;

    public WnmData(long bssid, String url, int method) {
        mBssid = bssid;
        mUrl = url;
        mMethod = method;
        mEss = false;
        mDelay = -1;
        mDeauthEvent = false;
    }

    public WnmData(long bssid, String url, boolean ess, int delay) {
        mBssid = bssid;
        mUrl = url;
        mEss = ess;
        mDelay = delay;
        mMethod = -1;
        mDeauthEvent = true;
    }

    public long getBssid() {
        return mBssid;
    }

    public String getUrl() {
        return mUrl;
    }

    public boolean isDeauthEvent() {
        return mDeauthEvent;
    }

    public int getMethod() {
        return mMethod;
    }

    public boolean isEss() {
        return mEss;
    }

    public int getDelay() {
        return mDelay;
    }
}
