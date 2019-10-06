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

package com.android.ike.ikev2.message;

import com.android.ike.ikev2.IkeTrafficSelector;
import com.android.ike.ikev2.exceptions.IkeException;

import java.nio.ByteBuffer;

/**
 * IkeTsPayload represents an Traffic Selector Initiator Payload or an Traffic Selector Responder
 * Payload.
 *
 * <p>Traffic Selector Initiator Payload and Traffic Selector Responder Payload have same format but
 * different payload types. They describe the address ranges and port ranges of Child SA initiator
 * and Child SA responder.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.13">RFC 7296, Internet Key Exchange
 *     Protocol Version 2 (IKEv2)</a>
 */
public final class IkeTsPayload extends IkePayload {
    // Length of reserved field in octets.
    private static final int TS_HEADER_RESERVED_LEN = 3;

    /** Number of Traffic Selectors */
    public final int numTs;
    public final IkeTrafficSelector[] trafficSelectors;

    IkeTsPayload(boolean critical, byte[] payloadBody, boolean isInitiator) throws IkeException {
        super((isInitiator ? PAYLOAD_TYPE_TS_INITIATOR : PAYLOAD_TYPE_TS_RESPONDER), critical);

        ByteBuffer inputBuffer = ByteBuffer.wrap(payloadBody);
        numTs = Byte.toUnsignedInt(inputBuffer.get());
        // Skip RESERVED byte
        inputBuffer.get(new byte[TS_HEADER_RESERVED_LEN]);

        // Decode Traffic Selectors
        byte[] tsBytes = new byte[inputBuffer.remaining()];
        inputBuffer.get(tsBytes);
        trafficSelectors = IkeTrafficSelector.decodeIkeTrafficSelectors(numTs, tsBytes);
    }

    /**
     * Encode Traffic Selector Payload to ByteBuffer.
     *
     * @param nextPayload type of payload that follows this payload.
     * @param byteBuffer destination ByteBuffer that stores encoded payload.
     */
    @Override
    protected void encodeToByteBuffer(@PayloadType int nextPayload, ByteBuffer byteBuffer) {
        throw new UnsupportedOperationException(
                "It is not supported to encode a " + getTypeString());
        //TODO: Implement it.
    }

    /**
     * Get entire payload length.
     *
     * @return entire payload length.
     */
    @Override
    protected int getPayloadLength() {
        throw new UnsupportedOperationException(
                "It is not supported to get payload length of " + getTypeString());
        //TODO: Implement it.
    }

    /**
     * Return the payload type as a String.
     *
     * @return the payload type as a String.
     */
    @Override
    public String getTypeString() {
        switch (payloadType) {
            case PAYLOAD_TYPE_ID_INITIATOR:
                return "Traffic Selector Initiator Payload";
            case PAYLOAD_TYPE_ID_RESPONDER:
                return "Traffic Selector Responder Payload";
            default:
                // Won't reach here.
                throw new IllegalArgumentException(
                        "Invalid Payload Type for Traffic Selector Payload.");
        }
    }
}
