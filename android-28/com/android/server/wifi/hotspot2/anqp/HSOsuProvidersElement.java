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

package com.android.server.wifi.hotspot2.anqp;

import android.net.wifi.WifiSsid;

import com.android.internal.annotations.VisibleForTesting;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The OSU Providers List vendor specific ANQP Element,
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.8.
 *
 * Format:
 *
 * | OSU SSID Length | OSU SSID | Number of OSU Providers | Provider #1 | ...
 *          1          variable             1                 variable
 *
 */
public class HSOsuProvidersElement extends ANQPElement {
    /**
     * Maximum length for a SSID.  Refer to IEEE 802.11-2012 Section 8.4.2.2
     * for more info.
     */
    @VisibleForTesting
    public static final int MAXIMUM_OSU_SSID_LENGTH = 32;

    private final WifiSsid mOsuSsid;
    private final List<OsuProviderInfo> mProviders;

    @VisibleForTesting
    public HSOsuProvidersElement(WifiSsid osuSsid, List<OsuProviderInfo> providers) {
        super(Constants.ANQPElementType.HSOSUProviders);
        mOsuSsid = osuSsid;
        mProviders = providers;
    }

    /**
     * Parse a HSOsuProvidersElement from the given buffer.
     *
     * @param payload The buffer to read from
     * @return {@link HSOsuProvidersElement}
     * @throws BufferUnderflowException
     * @throws ProtocolException
     */
    public static HSOsuProvidersElement parse(ByteBuffer payload)
            throws ProtocolException {
        int ssidLength = payload.get() & 0xFF;
        if (ssidLength > MAXIMUM_OSU_SSID_LENGTH) {
            throw new ProtocolException("Invalid SSID length: " + ssidLength);
        }
        byte[] ssidBytes = new byte[ssidLength];
        payload.get(ssidBytes);

        int numProviders = payload.get() & 0xFF;
        List<OsuProviderInfo> providers = new ArrayList<>();
        while (numProviders > 0) {
            providers.add(OsuProviderInfo.parse(payload));
            numProviders--;
        }

        return new HSOsuProvidersElement(WifiSsid.createFromByteArray(ssidBytes), providers);
    }

    public WifiSsid getOsuSsid() {
        return mOsuSsid;
    }

    public List<OsuProviderInfo> getProviders() {
        return Collections.unmodifiableList(mProviders);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof HSOsuProvidersElement)) {
            return false;
        }
        HSOsuProvidersElement that = (HSOsuProvidersElement) thatObject;
        return (mOsuSsid == null ? that.mOsuSsid == null : mOsuSsid.equals(that.mOsuSsid))
                && (mProviders == null ? that.mProviders == null
                        : mProviders.equals(that.mProviders));
    }

    @Override
    public int hashCode() {
        return Objects.hash(mOsuSsid, mProviders);
    }

    @Override
    public String toString() {
        return "OSUProviders{" + "mOsuSsid=" + mOsuSsid + ", mProviders=" + mProviders + "}";
    }
}
