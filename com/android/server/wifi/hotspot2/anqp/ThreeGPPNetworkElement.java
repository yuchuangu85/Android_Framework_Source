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

package com.android.server.wifi.hotspot2.anqp;

import com.android.internal.annotations.VisibleForTesting;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * The 3GPP Cellular Network ANQP Element, IEEE802.11-2012 section 8.4.4.11.
 * The value is embedded in a Generic container User Data (GUD).
 * Refer to Annex A of 3GPP TS 24.234 version 11.3.0 for more info:
 * (http://www.etsi.org/deliver/etsi_ts/124200_124299/124234/11.03.00_60/ts_124234v110300p.pdf).
 *
 * Format:
 * | GUD Version | Length | IEI 1 | ... | IEI N|
 *        1           1    variable
 *
 */
public class ThreeGPPNetworkElement extends ANQPElement {
    /**
     * The expected protocol version number of the Generic container User Data (GUD).
     */
    @VisibleForTesting
    public static final int GUD_VERSION_1 = 0;

    private final List<CellularNetwork> mNetworks;

    @VisibleForTesting
    public ThreeGPPNetworkElement(List<CellularNetwork> networks) {
        super(Constants.ANQPElementType.ANQP3GPPNetwork);
        mNetworks = networks;
    }

    /**
     * Parse a ThreeGPPNetworkElement from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @return {@link ThreeGPPNetworkElement}
     * @throws BufferUnderflowException
     * @throws ProtocolException
     */
    public static ThreeGPPNetworkElement parse(ByteBuffer payload)
            throws ProtocolException {
        // Verify version.
        int gudVersion = payload.get() & 0xFF;
        if (gudVersion != GUD_VERSION_1) {
            throw new ProtocolException("Unsupported GUD version: " + gudVersion);
        }

        // Verify length.
        int length = payload.get() & 0xFF;
        if (length != payload.remaining()) {
            throw new ProtocolException("Mismatch length and buffer size: length=" + length
                    + " bufferSize=" + payload.remaining());
        }

        // Parse each IEI (Information Element Identity) content.
        List<CellularNetwork> networks = new ArrayList<>();
        while (payload.hasRemaining()) {
            CellularNetwork network = CellularNetwork.parse(payload);
            if (network != null) {
                networks.add(network);
            }
        }
        return new ThreeGPPNetworkElement(networks);
    }

    public List<CellularNetwork> getNetworks() {
        return Collections.unmodifiableList(mNetworks);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof ThreeGPPNetworkElement)) {
            return false;
        }
        ThreeGPPNetworkElement that = (ThreeGPPNetworkElement) thatObject;
        return mNetworks.equals(that.mNetworks);

    }

    @Override
    public int hashCode() {
        return mNetworks.hashCode();
    }

    @Override
    public String toString() {
        return "ThreeGPPNetwork{mNetworks=" + mNetworks + "}";
    }
}
