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

import static com.android.ike.ikev2.message.IkePayload.PayloadType;

import android.annotation.IntDef;
import android.util.Pair;

import com.android.ike.ikev2.IkeSessionOptions;
import com.android.ike.ikev2.SaRecord.IkeSaRecord;
import com.android.ike.ikev2.exceptions.IkeException;
import com.android.ike.ikev2.exceptions.InvalidSyntaxException;
import com.android.ike.ikev2.exceptions.UnsupportedCriticalPayloadException;
import com.android.internal.annotations.VisibleForTesting;
import com.android.org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * IkeMessage represents an IKE message.
 *
 * <p>It contains all attributes and provides methods for encoding, decoding, encrypting and
 * decrypting.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7296#section-3">RFC 7296, Internet Key Exchange
 *     Protocol Version 2 (IKEv2)</a>
 */
public final class IkeMessage {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        MESSAGE_TYPE_IKE_INIT_RESP,
        MESSAGE_TYPE_IKE_AUTH_RESP,
        MESSAGE_TYPE_DELETE_IKE_REQ,
        MESSAGE_TYPE_DELETE_IKE_RESP,
        MESSAGE_TYPE_REKEY_IKE_REQ,
        MESSAGE_TYPE_REKEY_IKE_RESP,
        MESSAGE_TYPE_UNSUPPORTED_CRITICAL_PAYLOAD,
        MESSAGE_TYPE_INVALID_MAJOR_VERSION,
        MESSAGE_TYPE_INVALID_SYNTAX
    })
    public @interface MessageType {}

    // Message type for decoded IkeMessage.
    public static final int PROCEDURE_TYPE_BASE = 0;

    public static final int MESSAGE_TYPE_IKE_INIT_RESP = PROCEDURE_TYPE_BASE + 1;
    public static final int MESSAGE_TYPE_IKE_AUTH_RESP = PROCEDURE_TYPE_BASE + 2;
    public static final int MESSAGE_TYPE_DELETE_IKE_REQ = PROCEDURE_TYPE_BASE + 3;
    public static final int MESSAGE_TYPE_DELETE_IKE_RESP = PROCEDURE_TYPE_BASE + 4;
    public static final int MESSAGE_TYPE_REKEY_IKE_REQ = PROCEDURE_TYPE_BASE + 5;
    public static final int MESSAGE_TYPE_REKEY_IKE_RESP = PROCEDURE_TYPE_BASE + 6;

    public static final int NOTIFICATION_TYPE_BASE = PROCEDURE_TYPE_BASE + 100;
    public static final int MESSAGE_TYPE_UNSUPPORTED_CRITICAL_PAYLOAD =
            NOTIFICATION_TYPE_BASE + IkeNotifyPayload.NOTIFY_TYPE_UNSUPPORTED_CRITICAL_PAYLOAD;
    public static final int MESSAGE_TYPE_INVALID_MAJOR_VERSION =
            NOTIFICATION_TYPE_BASE + IkeNotifyPayload.NOTIFY_TYPE_INVALID_MAJOR_VERSION;
    public static final int MESSAGE_TYPE_INVALID_SYNTAX =
            NOTIFICATION_TYPE_BASE + IkeNotifyPayload.NOTIFY_TYPE_INVALID_SYNTAX;

    private static IIkeMessageHelper sIkeMessageHelper = new IkeMessageHelper();
    // Currently use Bouncy Castle as crypto security provider
    static final Provider SECURITY_PROVIDER = new BouncyCastleProvider();

    public final IkeHeader ikeHeader;
    public final List<IkePayload> ikePayloadList;
    /**
     * Conctruct an instance of IkeMessage. It is called by decode or for building outbound message.
     *
     * @param header the header of this IKE message
     * @param payloadList the list of decoded IKE payloads in this IKE message
     */
    public IkeMessage(IkeHeader header, List<IkePayload> payloadList) {
        ikeHeader = header;
        ikePayloadList = payloadList;
    }

    /**
     * Get security provider for IKE library
     *
     * <p>Use BouncyCastleProvider as the default security provider.
     *
     * @return the security provider of IKE library.
     */
    public static Provider getSecurityProvider() {
        // TODO: Move this getter out of IKE message package since not only this package uses it.
        return SECURITY_PROVIDER;
    }

    /**
     * Decode unencrypted IKE message body and create an instance of IkeMessage.
     *
     * <p>This method catches all RuntimeException during decoding incoming IKE packet.
     *
     * @param header the IKE header that is decoded but not validated.
     * @param inputPacket the byte array contains the whole IKE message.
     * @return the IkeMessage instance.
     * @throws IkeException if there is any protocol error.
     */
    public static IkeMessage decode(IkeHeader header, byte[] inputPacket) throws IkeException {
        return sIkeMessageHelper.decode(header, inputPacket);
    }

    /**
     * Decrypt and decode encrypted IKE message body and create an instance of IkeMessage.
     *
     * @param ikeSessionOptions IkeSessionOptions that contains cryptographic algorithm set.
     * @param ikeSaRecord ikeSaRecord where this packet is sent on.
     * @param ikeHeader header of IKE packet.
     * @param packet IKE packet as a byte array.
     * @return decoded IKE message.
     * @throws IkeException for decoding errors.
     * @throws GeneralSecurityException if there is any error during integrity check or decryption.
     */
    public static IkeMessage decode(
            IkeSessionOptions ikeSessionOptions,
            IkeSaRecord ikeSaRecord,
            IkeHeader ikeHeader,
            byte[] packet)
            throws IkeException, GeneralSecurityException {
        return sIkeMessageHelper.decode(ikeSessionOptions, ikeSaRecord, ikeHeader, packet);
    }

    private static List<IkePayload> decodePayloadList(
            @PayloadType int firstPayloadType, boolean isResp, byte[] unencryptedPayloads)
            throws IkeException {
        ByteBuffer inputBuffer = ByteBuffer.wrap(unencryptedPayloads);
        int currentPayloadType = firstPayloadType;
        // For supported payload
        List<IkePayload> supportedPayloadList = new LinkedList<>();
        // For unsupported critical payload
        List<Integer> unsupportedCriticalPayloadList = new LinkedList<>();

        while (currentPayloadType != IkePayload.PAYLOAD_TYPE_NO_NEXT) {
            Pair<IkePayload, Integer> pair =
                    IkePayloadFactory.getIkePayload(currentPayloadType, isResp, inputBuffer);
            IkePayload payload = pair.first;

            if (!(payload instanceof IkeUnsupportedPayload)) {
                supportedPayloadList.add(payload);
            } else if (payload.isCritical) {
                unsupportedCriticalPayloadList.add(payload.payloadType);
            }
            // Simply ignore unsupported uncritical payload.

            currentPayloadType = pair.second;
        }

        if (inputBuffer.remaining() > 0) {
            throw new InvalidSyntaxException(
                    "Malformed IKE Payload: Unexpected bytes at the end of packet.");
        }

        if (unsupportedCriticalPayloadList.size() > 0) {
            throw new UnsupportedCriticalPayloadException(unsupportedCriticalPayloadList);
        }
        return supportedPayloadList;
    }

    /**
     * Encode unencrypted IKE message.
     *
     * @return encoded IKE message in byte array.
     */
    public byte[] encode() {
        return sIkeMessageHelper.encode(this);
    }

    /**
     * Encrypt and encode packet.
     *
     * @param ikeSessionOptions IkeSessionOptions that contains cryptographic algorithm set.
     * @param ikeSaRecord ikeSaRecord where this packet is sent on.
     * @return encoded IKE message in byte array.
     */
    public byte[] encode(IkeSessionOptions ikeSessionOptions, IkeSaRecord ikeSaRecord) {
        return sIkeMessageHelper.encode(ikeSessionOptions, ikeSaRecord, this);
    }

    /**
     * Encode all payloads to a byte array.
     *
     * @return byte array contains all encoded payloads
     */
    private byte[] encodePayloads() {
        if (ikePayloadList.isEmpty()) {
            return new byte[0];
        }

        int payloadLengthSum = 0;
        for (IkePayload payload : ikePayloadList) {
            payloadLengthSum += payload.getPayloadLength();
        }

        ByteBuffer byteBuffer = ByteBuffer.allocate(payloadLengthSum);

        for (int i = 0; i < ikePayloadList.size() - 1; i++) {
            ikePayloadList
                    .get(i)
                    .encodeToByteBuffer(ikePayloadList.get(i + 1).payloadType, byteBuffer);
        }
        ikePayloadList
                .get(ikePayloadList.size() - 1)
                .encodeToByteBuffer(IkePayload.PAYLOAD_TYPE_NO_NEXT, byteBuffer);

        return byteBuffer.array();
    }

    /** Package */
    @VisibleForTesting
    byte[] attachEncodedHeader(byte[] encodedIkeBody) {
        ByteBuffer outputBuffer =
                ByteBuffer.allocate(IkeHeader.IKE_HEADER_LENGTH + encodedIkeBody.length);
        ikeHeader.encodeToByteBuffer(outputBuffer, encodedIkeBody.length);
        outputBuffer.put(encodedIkeBody);
        return outputBuffer.array();
    }

    @MessageType
    public int getMessageType() {
        return sIkeMessageHelper.getMessageType(this);
    }

    /**
     * IIkeMessageHelper provides interface for decoding, encoding and processing IKE packet.
     *
     * <p>IkeMessageHelper exists so that the interface is injectable for testing.
     */
    @VisibleForTesting
    public interface IIkeMessageHelper {
        /**
         * Check message type of decoded IKE message.
         *
         * @param ikeMessage IKE message to be checked.
         * @return message type.
         */
        @MessageType
        int getMessageType(IkeMessage ikeMessage);

        /**
         * Encode IKE message.
         *
         * @param ikeMessage message need to be encoded.
         * @return encoded IKE message in byte array.
         */
        byte[] encode(IkeMessage ikeMessage);

        /**
         * Encrypt and encode IKE message.
         *
         * @param ikeSessionOptions ikeSessionOptions that contains cryptographic algorithm set.
         * @param ikeSaRecord ikeSaRecord where this packet is sent on.
         * @param ikeMessage message need to be encoded.
         * @return encoded IKE message in byte array.
         */
        byte[] encode(
                IkeSessionOptions ikeSessionOptions,
                IkeSaRecord ikeSaRecord,
                IkeMessage ikeMessage);

        /**
         * Decode unencrypted packet.
         *
         * @param ikeHeader header of IKE packet.
         * @param packet IKE packet as a byte array.
         * @return decoded IKE message.
         * @throws IkeException for decoding errors.
         */
        IkeMessage decode(IkeHeader ikeHeader, byte[] packet) throws IkeException;

        /**
         * Decrypt and decode packet.
         *
         * @param ikeSessionOptions ikeSessionOptions that contains cryptographic algorithm set.
         * @param ikeSaRecord ikeSaRecord where this packet is sent on.
         * @param ikeHeader header of IKE packet.
         * @param packet IKE packet as a byte array.
         * @return decoded IKE message.
         * @throws IkeException for decoding errors.
         */
        IkeMessage decode(
                IkeSessionOptions ikeSessionOptions,
                IkeSaRecord ikeSaRecord,
                IkeHeader ikeHeader,
                byte[] packet)
                throws IkeException, GeneralSecurityException;
    }

    /** IkeMessageHelper provides methods for decoding, encoding and processing IKE packet. */
    public static final class IkeMessageHelper implements IIkeMessageHelper {
        @Override
        public byte[] encode(IkeMessage ikeMessage) {
            byte[] encodedIkeBody = ikeMessage.encodePayloads();
            return ikeMessage.attachEncodedHeader(encodedIkeBody);
        }

        @Override
        public byte[] encode(
                IkeSessionOptions ikeSessionOptions,
                IkeSaRecord ikeSaRecord,
                IkeMessage ikeMessage) {
            // TODO: Extract crypto attributes and call encrypt()
            return null;
        }

        //TODO: Create and use a container class for crypto algorithms and keys.
        private byte[] encryptAndEncode(
                IkeHeader ikeHeader,
                @PayloadType int firstPayload,
                byte[] unencryptedPayloads,
                Mac integrityMac,
                int checksumLen,
                Cipher encryptCipher,
                SecretKey eKey) {
            IkeSkPayload skPayload =
                    new IkeSkPayload(
                            ikeHeader,
                            firstPayload,
                            unencryptedPayloads,
                            integrityMac,
                            checksumLen,
                            encryptCipher,
                            eKey);

            ByteBuffer outputBuffer =
                    ByteBuffer.allocate(IkeHeader.IKE_HEADER_LENGTH + skPayload.getPayloadLength());
            ikeHeader.encodeToByteBuffer(outputBuffer, skPayload.getPayloadLength());
            skPayload.encodeToByteBuffer(firstPayload, outputBuffer);

            return outputBuffer.array();
        }

        @Override
        public IkeMessage decode(IkeHeader header, byte[] inputPacket) throws IkeException {
            header.checkInboundValidOrThrow(inputPacket.length);

            byte[] unencryptedPayloads =
                    Arrays.copyOfRange(
                            inputPacket, IkeHeader.IKE_HEADER_LENGTH, inputPacket.length);

            try {
                List<IkePayload> supportedPayloadList =
                        decodePayloadList(
                                header.nextPayloadType, header.isResponseMsg, unencryptedPayloads);
                return new IkeMessage(header, supportedPayloadList);
            } catch (NegativeArraySizeException | BufferUnderflowException e) {
                // Invalid length error when parsing payload bodies.
                throw new InvalidSyntaxException("Malformed IKE Payload");
            }
        }

        @Override
        public IkeMessage decode(
                IkeSessionOptions ikeSessionOptions,
                IkeSaRecord ikeSaRecord,
                IkeHeader ikeHeader,
                byte[] packet)
                throws IkeException, GeneralSecurityException {
            // TODO: Extract crypto params and call private decode method.
            return null;
        }

        private IkeMessage decode(
                IkeHeader header,
                byte[] inputPacket,
                Mac integrityMac,
                int checksumLen,
                Cipher decryptCipher,
                SecretKey dKey)
                throws IkeException, GeneralSecurityException {

            header.checkInboundValidOrThrow(inputPacket.length);

            if (header.nextPayloadType != IkePayload.PAYLOAD_TYPE_SK) {
                // TODO: b/123372339 Handle message containing unprotected payloads.
                throw new UnsupportedOperationException("Message contains unprotected payloads");
            }

            try {
                Pair<IkeSkPayload, Integer> pair =
                        IkePayloadFactory.getIkeSkPayload(
                                inputPacket, integrityMac, checksumLen, decryptCipher, dKey);
                IkeSkPayload skPayload = pair.first;
                int firstPayloadType = pair.second;

                List<IkePayload> supportedPayloadList =
                        decodePayloadList(
                                firstPayloadType,
                                header.isResponseMsg,
                                skPayload.getUnencryptedPayloads());

                return new IkeMessage(header, supportedPayloadList);
            } catch (NegativeArraySizeException | BufferUnderflowException e) {
                // Invalid length error when parsing payload bodies.
                throw new InvalidSyntaxException("Malformed IKE Payload");
            }
        }

        @Override
        @MessageType
        public int getMessageType(IkeMessage ikeMessage) {
            // TODO: Implement it.
            return 0;
        }
    }

    /**
     * For setting mocked IIkeMessageHelper for testing
     *
     * @param helper the mocked IIkeMessageHelper
     */
    public static void setIkeMessageHelper(IIkeMessageHelper helper) {
        sIkeMessageHelper = helper;
    }
}
