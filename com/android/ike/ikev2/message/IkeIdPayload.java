/*
 * Copyright (C) 2018 The Android Open Source Project
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

import com.android.ike.ikev2.IkeIdentification;
import com.android.ike.ikev2.IkeIdentification.IkeIpv4AddrIdentification;
import com.android.ike.ikev2.IkeIdentification.IkeIpv6AddrIdentification;
import com.android.ike.ikev2.exceptions.AuthenticationFailedException;
import com.android.ike.ikev2.exceptions.IkeException;
import com.android.ike.ikev2.exceptions.InvalidSyntaxException;
import com.android.ike.ikev2.message.IkePayload.PayloadType;

import java.nio.ByteBuffer;

/**
 * IkeIdPayload represents an Identification Initiator Payload or an Identification Responder
 * Payload.
 *
 * <p>Identification Initiator Payload and Identification Responder Payload have same format but
 * different payload type.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.5">RFC 7296, Internet Key Exchange
 *     Protocol Version 2 (IKEv2)</a>
 */
public final class IkeIdPayload extends IkePayload {
    // Length of ID Payload header in octets.
    private static final int ID_HEADER_LEN = 4;
    // Length of reserved field in octets.
    private static final int ID_HEADER_RESERVED_LEN = 3;

    public final IkeIdentification ikeId;

    /**
     * Construct IkeIdPayload for received IKE packet in the context of {@link IkePayloadFactory}.
     *
     * @param critical indicates if it is a critical payload.
     * @param payloadBody payload body in byte array.
     * @param isInitiator indicates whether this payload contains the ID of IKE initiator or IKE
     *     responder.
     * @throws IkeException for decoding error.
     */
    IkeIdPayload(boolean critical, byte[] payloadBody, boolean isInitiator) throws IkeException {
        super((isInitiator ? PAYLOAD_TYPE_ID_INITIATOR : PAYLOAD_TYPE_ID_RESPONDER), critical);
        // TODO: b/119791832 Add helper method for checking payload body length in superclass.
        if (payloadBody.length <= ID_HEADER_LEN) {
            throw new InvalidSyntaxException(getTypeString() + " is too short.");
        }

        ByteBuffer inputBuffer = ByteBuffer.wrap(payloadBody);
        int idType = Byte.toUnsignedInt(inputBuffer.get());

        // Skip reserved field
        inputBuffer.get(new byte[ID_HEADER_RESERVED_LEN]);

        byte[] idData = new byte[payloadBody.length - ID_HEADER_LEN];
        inputBuffer.get(idData);

        switch (idType) {
            case IkeIdentification.ID_TYPE_IPV4_ADDR:
                ikeId = new IkeIpv4AddrIdentification(idData);
                return;
            case IkeIdentification.ID_TYPE_FQDN:
                // Fall through
            case IkeIdentification.ID_TYPE_RFC822_ADDR:
                throw new UnsupportedOperationException("ID type is not supported currently.");
            case IkeIdentification.ID_TYPE_IPV6_ADDR:
                ikeId = new IkeIpv6AddrIdentification(idData);
                return;
            case IkeIdentification.ID_TYPE_DER_ASN1_DN:
                // Fall through
            case IkeIdentification.ID_TYPE_DER_ASN1_GN:
                // Fall through
            case IkeIdentification.ID_TYPE_KEY_ID:
                throw new UnsupportedOperationException("ID type is not supported currently.");
            default:
                throw new AuthenticationFailedException("Unsupported ID type: " + idType);
        }
    }

    /**
     * Construct IkeIdPayload for an outbound IKE packet.
     *
     * @param isInitiator indicates whether this payload contains the ID of IKE initiator or IKE
     *     responder.
     * @param ikeId the IkeIdentification.
     */
    public IkeIdPayload(boolean isInitiator, IkeIdentification ikeId) {
        super((isInitiator ? PAYLOAD_TYPE_ID_INITIATOR : PAYLOAD_TYPE_ID_RESPONDER), false);
        this.ikeId = ikeId;
    }

    /**
     * Encode Identification Payload to ByteBuffer.
     *
     * @param nextPayload type of payload that follows this payload.
     * @param byteBuffer destination ByteBuffer that stores encoded payload.
     */
    @Override
    protected void encodeToByteBuffer(@PayloadType int nextPayload, ByteBuffer byteBuffer) {
        encodePayloadHeaderToByteBuffer(nextPayload, getPayloadLength(), byteBuffer);
        byteBuffer
                .put((byte) ikeId.idType)
                .put(new byte[ID_HEADER_RESERVED_LEN])
                .put(ikeId.getEncodedIdData());
    }

    /**
     * Get entire payload length.
     *
     * @return entire payload length.
     */
    @Override
    protected int getPayloadLength() {
        return GENERIC_HEADER_LENGTH + ID_HEADER_LEN + ikeId.getEncodedIdData().length;
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
                return "Identification Initiator Payload";
            case PAYLOAD_TYPE_ID_RESPONDER:
                return "Identification Responder Payload";
            default:
                // Won't reach here.
                throw new IllegalArgumentException(
                        "Invalid Payload Type for Identification Payload.");
        }
    }
}
