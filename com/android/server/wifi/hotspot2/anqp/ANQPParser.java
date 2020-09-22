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
import com.android.server.wifi.ByteBufferReader;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Factory to build a collection of 802.11u ANQP elements from a byte buffer.
 */
public class ANQPParser {
    /**
     * The OI value for Hotspot 2.0 ANQP-element.
     */
    @VisibleForTesting
    public static final int VENDOR_SPECIFIC_HS20_OI = 0x506F9A;

    /**
     * The Type value for Hotspot 2.0 ANQP-element.
     */
    @VisibleForTesting
    public static final int VENDOR_SPECIFIC_HS20_TYPE = 0x11;

    /**
     * Parse an ANQP element from the pass-in byte buffer.
     *
     * Note: Each Hotspot 2.0 Release 2 element will be wrapped inside a Vendor Specific element
     * in the ANQP response from the AP.  However, the lower layer (e.g. wpa_supplicant) should
     * already take care of parsing those elements out of Vendor Specific elements.  To be safe,
     * we will parse the Vendor Specific elements for non-Hotspot 2.0 Release elements or in
     * the case they're not parsed by the lower layer.
     *
     * @param infoID The ANQP element type
     * @param payload The buffer to read from
     * @return {@link com.android.server.wifi.hotspot2.anqp.ANQPElement}
     * @throws BufferUnderflowException
     * @throws ProtocolException
     */
    public static ANQPElement parseElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        switch (infoID) {
            case ANQPVenueName:
                return VenueNameElement.parse(payload);
            case ANQPRoamingConsortium:
                return RoamingConsortiumElement.parse(payload);
            case ANQPIPAddrAvailability:
                return IPAddressTypeAvailabilityElement.parse(payload);
            case ANQPNAIRealm:
                return NAIRealmElement.parse(payload);
            case ANQP3GPPNetwork:
                return ThreeGPPNetworkElement.parse(payload);
            case ANQPDomName:
                return DomainNameElement.parse(payload);
            case ANQPVendorSpec:
                return parseVendorSpecificElement(payload);
            default:
                throw new ProtocolException("Unknown element ID: " + infoID);
        }
    }

    /**
     * Parse a Hotspot 2.0 Release 2 ANQP element from the pass-in byte buffer.
     *
     * @param infoID The ANQP element ID
     * @param payload The buffer to read from
     * @return {@link com.android.server.wifi.hotspot2.anqp.ANQPElement}
     * @throws BufferUnderflowException
     * @throws ProtocolException
     */
    public static ANQPElement parseHS20Element(Constants.ANQPElementType infoID,
            ByteBuffer payload) throws ProtocolException {
        switch (infoID) {
            case HSFriendlyName:
                return HSFriendlyNameElement.parse(payload);
            case HSWANMetrics:
                return HSWanMetricsElement.parse(payload);
            case HSConnCapability:
                return HSConnectionCapabilityElement.parse(payload);
            case HSOSUProviders:
                return HSOsuProvidersElement.parse(payload);
            default:
                throw new ProtocolException("Unknown element ID: " + infoID);
        }
    }

    /**
     * Parse the ANQP vendor specific element.  Currently only supports the vendor specific
     * element that contained Hotspot 2.0 ANQP-element.
     *
     * Format of a ANQP Vendor Specific element:
     * | OI | Type | Subtype | Reserved | Payload |
     *   3     1        1         1       variable
     *
     * @param payload The buffer to read from
     * @return {@link ANQPElement}
     * @throws BufferUnderflowException
     * @throws ProtocolException
     */
    private static ANQPElement parseVendorSpecificElement(ByteBuffer payload)
            throws ProtocolException {
        int oi = (int) ByteBufferReader.readInteger(payload, ByteOrder.BIG_ENDIAN, 3);
        int type = payload.get() & 0xFF;

        if (oi != VENDOR_SPECIFIC_HS20_OI || type != VENDOR_SPECIFIC_HS20_TYPE) {
            throw new ProtocolException("Unsupported vendor specific OI=" + oi + " type=" + type);
        }

        int subType = payload.get() & 0xFF;
        Constants.ANQPElementType hs20ID = Constants.mapHS20Element(subType);
        if (hs20ID == null) {
            throw new ProtocolException("Unsupported subtype: " + subType);
        }
        payload.get();     // Skip the reserved byte
        return parseHS20Element(hs20ID, payload);
    }
}
