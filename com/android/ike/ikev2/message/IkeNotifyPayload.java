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

import android.annotation.IntDef;
import android.util.ArraySet;

import com.android.ike.ikev2.exceptions.IkeException;
import com.android.ike.ikev2.exceptions.InvalidSyntaxException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

/**
 * IkeNotifyPayload represents a Notify Payload.
 *
 * <p>As instructed by RFC 7296, for IKE SA concerned Notify Payload, Protocol ID and SPI Size must
 * be zero. Unrecognized notify message type must be ignored but should be logged.
 *
 * <p>Critical bit for this payload must be ignored in received packet and must not be set in
 * outbound packet.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7296">RFC 7296, Internet Key Exchange Protocol
 *     Version 2 (IKEv2)</a>
 */
public final class IkeNotifyPayload extends IkePayload {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        NOTIFY_TYPE_UNSUPPORTED_CRITICAL_PAYLOAD,
        NOTIFY_TYPE_INVALID_MAJOR_VERSION,
        NOTIFY_TYPE_INVALID_SYNTAX,
        NOTIFY_TYPE_NO_PROPOSAL_CHOSEN,
        NOTIFY_TYPE_INVALID_SELECTORS,
        NOTIFY_TYPE_CHILD_SA_NOT_FOUND,
        NOTIFY_TYPE_NAT_DETECTION_SOURCE_IP,
        NOTIFY_TYPE_NAT_DETECTION_DESTINATION_IP,
        NOTIFY_TYPE_REKEY_SA
    })
    public @interface NotifyType {}

    public static final int NOTIFY_TYPE_UNSUPPORTED_CRITICAL_PAYLOAD = 1;
    public static final int NOTIFY_TYPE_INVALID_MAJOR_VERSION = 5;
    public static final int NOTIFY_TYPE_INVALID_SYNTAX = 7;
    public static final int NOTIFY_TYPE_NO_PROPOSAL_CHOSEN = 14;
    public static final int NOTIFY_TYPE_AUTHENTICATION_FAILED = 24;
    public static final int NOTIFY_TYPE_INVALID_SELECTORS = 39;
    public static final int NOTIFY_TYPE_CHILD_SA_NOT_FOUND = 44;

    public static final int NOTIFY_TYPE_NAT_DETECTION_SOURCE_IP = 16388;
    public static final int NOTIFY_TYPE_NAT_DETECTION_DESTINATION_IP = 16389;
    public static final int NOTIFY_TYPE_REKEY_SA = 16393;
    // TODO: List all supported notify types.

    private static final int NOTIFY_HEADER_LEN = 4;

    private static final String NAT_DETECTION_DIGEST_ALGORITHM = "SHA-1";

    private static final Set<Integer> VALID_NOTIFY_TYPES_FOR_CHILD_SA;

    static {
        VALID_NOTIFY_TYPES_FOR_CHILD_SA = new ArraySet<>();
        VALID_NOTIFY_TYPES_FOR_CHILD_SA.add(NOTIFY_TYPE_INVALID_SELECTORS);
        VALID_NOTIFY_TYPES_FOR_CHILD_SA.add(NOTIFY_TYPE_CHILD_SA_NOT_FOUND);
        VALID_NOTIFY_TYPES_FOR_CHILD_SA.add(NOTIFY_TYPE_REKEY_SA);
    }

    public final int protocolId;
    public final byte spiSize;
    public final int notifyType;
    public final int spi;
    public final byte[] notifyData;

    /**
     * Construct an instance of IkeNotifyPayload in the context of IkePayloadFactory
     *
     * @param critical indicates if this payload is critical. Ignored in supported payload as
     *     instructed by the RFC 7296.
     * @param payloadBody payload body in byte array
     * @throws IkeException if there is any error
     */
    IkeNotifyPayload(boolean isCritical, byte[] payloadBody) throws IkeException {
        super(PAYLOAD_TYPE_NOTIFY, isCritical);

        ByteBuffer inputBuffer = ByteBuffer.wrap(payloadBody);

        protocolId = Byte.toUnsignedInt(inputBuffer.get());
        spiSize = inputBuffer.get();
        notifyType = Short.toUnsignedInt(inputBuffer.getShort());

        // Validate syntax of spiSize, protocolId and notifyType.
        // Reference: <https://tools.ietf.org/html/rfc7296#page-100>
        if (spiSize == SPI_LEN_IPSEC) {
            // For message concerning existing Child SA
            validateNotifyPayloadForExistingChildSa();
            spi = inputBuffer.getInt();

        } else if (spiSize == SPI_LEN_NOT_INCLUDED) {
            // For message concerning IKE SA or for new Child SA that to be negotiated.
            validateNotifyPayloadForIkeAndNewChild();
            spi = SPI_NOT_INCLUDED;

        } else {
            throw new InvalidSyntaxException("Invalid SPI Size: " + spiSize);
        }

        notifyData = new byte[payloadBody.length - NOTIFY_HEADER_LEN];
        inputBuffer.get(notifyData);
    }

    private void validateNotifyPayloadForExistingChildSa() throws InvalidSyntaxException {
        if (protocolId != PROTOCOL_ID_AH && protocolId != PROTOCOL_ID_ESP) {
            throw new InvalidSyntaxException(
                    "Expected Procotol ID AH(2) or ESP(3): Protocol ID is " + protocolId);
        }

        if (!VALID_NOTIFY_TYPES_FOR_CHILD_SA.contains(notifyType)) {
            throw new InvalidSyntaxException(
                    "Expected Notify Type for existing Child SA: Notify Type is " + notifyType);
        }
    }

    private void validateNotifyPayloadForIkeAndNewChild() throws InvalidSyntaxException {
        if (protocolId != PROTOCOL_ID_UNSET) {
            throw new InvalidSyntaxException(
                    "Expected Procotol ID unset: Protocol ID is " + protocolId);
        }

        if (notifyType == NOTIFY_TYPE_INVALID_SELECTORS
                || notifyType == NOTIFY_TYPE_CHILD_SA_NOT_FOUND) {
            throw new InvalidSyntaxException(
                    "Expected Notify Type concerning IKE SA or new Child SA under negotiation"
                            + ": Notify Type is "
                            + notifyType);
        }
    }

    /**
     * Generate NAT DETECTION notification data.
     *
     * <p>This method calculates NAT DETECTION notification data which is a SHA-1 digest of the IKE
     * initiator's SPI, IKE responder's SPI, IP address and port. Source address and port should be
     * used for generating NAT_DETECTION_SOURCE_IP data. Destination address and port should be used
     * for generating NAT_DETECTION_DESTINATION_IP data.
     *
     * @param initiatorIkeSpi the SPI of IKE initiator
     * @param responderIkeSpi the SPI of IKE responder
     * @param ipAddress the IP address
     * @param port the port
     * @return the generated NAT DETECTION notification data as a byte array.
     * @throws NoSuchAlgorithmException when "SHA-1" is not supported by the security provider.
     */
    public static byte[] generateNatDetectionData(
            long initiatorIkeSpi, long responderIkeSpi, InetAddress ipAddress, int port)
            throws NoSuchAlgorithmException {
        byte[] rawIpAddr = ipAddress.getAddress();

        ByteBuffer byteBuffer =
                ByteBuffer.allocate(2 * SPI_LEN_IKE + rawIpAddr.length + IP_PORT_LEN);
        byteBuffer
                .putLong(initiatorIkeSpi)
                .putLong(responderIkeSpi)
                .put(rawIpAddr)
                .putShort((short) port);

        MessageDigest natDetectionDataDigest =
                MessageDigest.getInstance(
                        NAT_DETECTION_DIGEST_ALGORITHM, IkeMessage.getSecurityProvider());
        return natDetectionDataDigest.digest(byteBuffer.array());
    }

    /**
     * Encode Notify payload to ByteBuffer.
     *
     * @param nextPayload type of payload that follows this payload.
     * @param byteBuffer destination ByteBuffer that stores encoded payload.
     */
    @Override
    protected void encodeToByteBuffer(@PayloadType int nextPayload, ByteBuffer byteBuffer) {
        encodePayloadHeaderToByteBuffer(nextPayload, getPayloadLength(), byteBuffer);
        byteBuffer.put((byte) protocolId).put(spiSize).putShort((short) notifyType);
        if (spiSize == SPI_LEN_IPSEC) {
            byteBuffer.putInt(spi);
        }
        byteBuffer.put(notifyData);
    }

    /**
     * Get entire payload length.
     *
     * @return entire payload length.
     */
    @Override
    protected int getPayloadLength() {
        return GENERIC_HEADER_LENGTH + NOTIFY_HEADER_LEN + spiSize + notifyData.length;
    }

    protected IkeNotifyPayload(
            @ProtocolId int protocolId,
            byte spiSize,
            int spi,
            @NotifyType int notifyType,
            byte[] notifyData) {
        super(PAYLOAD_TYPE_NOTIFY, false);
        this.protocolId = protocolId;
        this.spiSize = spiSize;
        this.spi = spi;
        this.notifyType = notifyType;
        this.notifyData = notifyData;
    }

    /**
     * Construct IkeNotifyPayload concerning either an IKE SA or Child SA that is going to be
     * negotiated.
     *
     * @param notifyType the notify type concerning IKE SA
     * @param notifytData status or error data transmitted. Values for this field are notify type
     *     specific.
     */
    public IkeNotifyPayload(@NotifyType int notifyType, byte[] notifyData) {
        this(PROTOCOL_ID_UNSET, SPI_LEN_NOT_INCLUDED, SPI_NOT_INCLUDED, notifyType, notifyData);
        try {
            validateNotifyPayloadForIkeAndNewChild();
        } catch (InvalidSyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Construct IkeNotifyPayload concerning existing Child SA
     *
     * @param notifyType the notify type concerning Child SA
     * @param notifytData status or error data transmitted. Values for this field are notify type
     *     specific.
     */
    public IkeNotifyPayload(
            @ProtocolId int protocolId, int spi, @NotifyType int notifyType, byte[] notifyData) {
        this(protocolId, SPI_LEN_IPSEC, spi, notifyType, notifyData);
        try {
            validateNotifyPayloadForExistingChildSa();
        } catch (InvalidSyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Return the payload type as a String.
     *
     * @return the payload type as a String.
     */
    @Override
    public String getTypeString() {
        return "Notify Payload";
    }
}
