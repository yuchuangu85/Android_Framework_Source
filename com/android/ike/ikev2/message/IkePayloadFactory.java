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

import android.util.Pair;

import com.android.ike.ikev2.exceptions.IkeException;
import com.android.ike.ikev2.exceptions.InvalidSyntaxException;
import com.android.internal.annotations.VisibleForTesting;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * IkePayloadFactory is used for creating IkePayload according to is type.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7296#section-3">RFC 7296, Internet Key Exchange
 *     Protocol Version 2 (IKEv2)</a>
 */
final class IkePayloadFactory {

    // Critical bit is set and following reserved 7 bits are unset.
    private static final byte PAYLOAD_HEADER_CRITICAL_BIT_SET = (byte) 0x80;

    private static boolean isCriticalPayload(byte flagByte) {
        // Reserved 7 bits following critical bit must be ignore on receipt.
        return (flagByte & PAYLOAD_HEADER_CRITICAL_BIT_SET) == PAYLOAD_HEADER_CRITICAL_BIT_SET;
    }

    /** Default IIkePayloadDecoder instance used for constructing IkePayload */
    static IIkePayloadDecoder sDecoderInstance = new IkePayloadDecoder();

    /**
     * IkePayloadDecoder implements IIkePayloadDecoder for constructing IkePayload from decoding
     * received message.
     *
     * <p>Package private
     */
    @VisibleForTesting
    static class IkePayloadDecoder implements IIkePayloadDecoder {
        @Override
        public IkePayload decodeIkePayload(
                int payloadType, boolean isCritical, boolean isResp, byte[] payloadBody)
                throws IkeException {
            switch (payloadType) {
                    // TODO: Add cases for creating supported payloads.
                case IkePayload.PAYLOAD_TYPE_SA:
                    return new IkeSaPayload(isCritical, isResp, payloadBody);
                case IkePayload.PAYLOAD_TYPE_KE:
                    return new IkeKePayload(isCritical, payloadBody);
                case IkePayload.PAYLOAD_TYPE_ID_INITIATOR:
                    return new IkeIdPayload(isCritical, payloadBody, true);
                case IkePayload.PAYLOAD_TYPE_ID_RESPONDER:
                    return new IkeIdPayload(isCritical, payloadBody, false);
                case IkePayload.PAYLOAD_TYPE_CERT:
                    return IkeCertPayload.getIkeCertPayload(isCritical, payloadBody);
                case IkePayload.PAYLOAD_TYPE_AUTH:
                    return IkeAuthPayload.getIkeAuthPayload(isCritical, payloadBody);
                case IkePayload.PAYLOAD_TYPE_NONCE:
                    return new IkeNoncePayload(isCritical, payloadBody);
                case IkePayload.PAYLOAD_TYPE_NOTIFY:
                    return new IkeNotifyPayload(isCritical, payloadBody);
                case IkePayload.PAYLOAD_TYPE_DELETE:
                    return new IkeDeletePayload(isCritical, payloadBody);
                case IkePayload.PAYLOAD_TYPE_VENDOR:
                    return new IkeVendorPayload(isCritical, payloadBody);
                case IkePayload.PAYLOAD_TYPE_TS_INITIATOR:
                    return new IkeTsPayload(isCritical, payloadBody, true);
                case IkePayload.PAYLOAD_TYPE_TS_RESPONDER:
                    return new IkeTsPayload(isCritical, payloadBody, false);
                default:
                    return new IkeUnsupportedPayload(payloadType, isCritical);
            }
        }
    }

    /**
     * Construct an instance of IkePayload according to its payload type.
     *
     * @param payloadType the current payload type. All supported types will fall in {@link
     *     IkePayload.PayloadType}
     * @param input the encoded IKE message body containing all payloads. Position of it will
     *     increment.
     * @return a Pair including IkePayload and next payload type.
     */
    protected static Pair<IkePayload, Integer> getIkePayload(
            int payloadType, boolean isResp, ByteBuffer input) throws IkeException {
        int nextPayloadType = (int) input.get();
        // read critical bit
        boolean isCritical = isCriticalPayload(input.get());

        int payloadLength = Short.toUnsignedInt(input.getShort());
        if (payloadLength <= IkePayload.GENERIC_HEADER_LENGTH) {
            throw new InvalidSyntaxException(
                    "Invalid Payload Length: Payload length is too short.");
        }
        int bodyLength = payloadLength - IkePayload.GENERIC_HEADER_LENGTH;
        if (bodyLength > input.remaining()) {
            // It is not clear whether previous payloads or current payload has invalid payload
            // length.
            throw new InvalidSyntaxException("Invalid Payload Length: Payload length is too long.");
        }
        byte[] payloadBody = new byte[bodyLength];
        input.get(payloadBody);

        IkePayload payload =
                sDecoderInstance.decodeIkePayload(payloadType, isCritical, isResp, payloadBody);
        return new Pair(payload, nextPayloadType);
    }

    /**
     * Construct an instance of IkeSkPayload by decrypting the received message.
     *
     * @param message the byte array contains the whole IKE message.
     * @param integrityMac the initialized Mac for integrity check.
     * @param checksumLen the checksum length of negotiated integrity algorithm.
     * @param decryptCipher the uninitialized Cipher for doing decryption.
     * @param dKey the decryption key.
     * @return a pair including IkePayload and next payload type.
     * @throws IkeException for decoding errors.
     * @throws GeneralSecurityException if there is any error during integrity check or decryption.
     */
    protected static Pair<IkeSkPayload, Integer> getIkeSkPayload(
            byte[] message,
            Mac integrityMac,
            int checksumLen,
            Cipher decryptCipher,
            SecretKey dKey)
            throws IkeException, GeneralSecurityException {
        ByteBuffer input =
                ByteBuffer.wrap(
                        message,
                        IkeHeader.IKE_HEADER_LENGTH,
                        message.length - IkeHeader.IKE_HEADER_LENGTH);

        int nextPayloadType = (int) input.get();
        // read critical bit
        boolean isCritical = isCriticalPayload(input.get());

        int payloadLength = Short.toUnsignedInt(input.getShort());

        int bodyLength = message.length - IkeHeader.IKE_HEADER_LENGTH;
        if (bodyLength < payloadLength) {
            throw new InvalidSyntaxException(
                    "Invalid length of SK Payload: Payload length is too long.");
        } else if (bodyLength > payloadLength) {
            // According to RFC 7296, SK Payload must be the last payload and for CREATE_CHILD_SA,
            // IKE_AUTH and INFORMATIONAL exchanges, message following the header is encrypted. Thus
            // this implementaion only accepts that SK Payload to be the only payload. Any IKE
            // packet violating this format will be treated as invalid. A request violating this
            // format will be rejected and replied with an error notification.
            throw new InvalidSyntaxException(
                    "Invalid length of SK Payload: Payload length is too short"
                            + " or SK Payload is not the only payload.");
        }

        IkeSkPayload payload =
                new IkeSkPayload(
                        isCritical,
                        message,
                        integrityMac,
                        checksumLen,
                        decryptCipher,
                        dKey);
        return new Pair(payload, nextPayloadType);
    }

    /**
     * IIkePayloadDecoder provides a package private interface for constructing IkePayload from
     * decoding received message.
     *
     * <p>IIkePayloadDecoder exists so that the interface is injectable for testing.
     */
    @VisibleForTesting
    interface IIkePayloadDecoder {
        IkePayload decodeIkePayload(
                int payloadType, boolean isCritical, boolean isResp, byte[] payloadBody)
                throws IkeException;
    }
}
