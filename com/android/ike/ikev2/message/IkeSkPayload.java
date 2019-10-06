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

import com.android.ike.ikev2.exceptions.IkeException;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * IkeSkPayload represents a Encrypted Payload.
 *
 * <p>It contains other payloads in encrypted form. It is must be the last payload in the message.
 * It should be the only payload in this implementation.
 *
 * <p>Critical bit must be ignored when doing decoding.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7296#page-105">RFC 7296, Internet Key Exchange
 *     Protocol Version 2 (IKEv2)</a>
 */
public final class IkeSkPayload extends IkePayload {

    private final IkeEncryptedPayloadBody mIkeEncryptedPayloadBody;

    /**
     * Construct an instance of IkeSkPayload from decrypting an incoming packet.
     *
     * @param critical indicates if it is a critical payload.
     * @param message the byte array contains the whole IKE message.
     * @param integrityMac the initialized Mac for integrity check.
     * @param checksumLen the checksum length of negotiated integrity algorithm.
     * @param decryptCipher the uninitialized Cipher for doing decryption.
     * @param dKey the decryption key.
     */
    IkeSkPayload(
            boolean critical,
            byte[] message,
            Mac integrityMac,
            int checksumLen,
            Cipher decryptCipher,
            SecretKey dKey)
            throws IkeException, GeneralSecurityException {
        super(PAYLOAD_TYPE_SK, critical);

        mIkeEncryptedPayloadBody =
                new IkeEncryptedPayloadBody(
                        message, integrityMac, checksumLen, decryptCipher, dKey);
    }

    /**
     * Construct an instance of IkeSkPayload for building outbound packet.
     *
     * @param ikeHeader the IKE header.
     * @param firstPayloadType the type of first payload nested in SkPayload.
     * @param unencryptedPayloads the encoded payload list to protect.
     * @param integrityMac the initialized Mac for calculating integrity checksum
     * @param checksumLen the checksum length of negotiated integrity algorithm.
     * @param encryptCipher the uninitialized Cipher for doing encryption.
     * @param eKey the encryption key.
     */
    IkeSkPayload(
            IkeHeader ikeHeader,
            @PayloadType int firstPayloadType,
            byte[] unencryptedPayloads,
            Mac integrityMac,
            int checksumLen,
            Cipher encryptCipher,
            SecretKey eKey) {
        super(PAYLOAD_TYPE_SK, false);

        mIkeEncryptedPayloadBody =
                new IkeEncryptedPayloadBody(
                        ikeHeader,
                        firstPayloadType,
                        unencryptedPayloads,
                        integrityMac,
                        checksumLen,
                        encryptCipher,
                        eKey);
    }

    /**
     * Return unencrypted payload list
     *
     * @return unencrypted payload list in a byte array.
     */
    public byte[] getUnencryptedPayloads() {
        return mIkeEncryptedPayloadBody.getUnencryptedData();
    }

    // TODO: Add another constructor for AEAD protected payload.

    /**
     * Encode this payload to a ByteBuffer.
     *
     * @param nextPayload type of payload that follows this payload.
     * @param byteBuffer destination ByteBuffer that stores encoded payload.
     */
    @Override
    protected void encodeToByteBuffer(@PayloadType int nextPayload, ByteBuffer byteBuffer) {
        encodePayloadHeaderToByteBuffer(nextPayload, getPayloadLength(), byteBuffer);
        byteBuffer.put(mIkeEncryptedPayloadBody.encode());
    }

    /**
     * Get entire payload length.
     *
     * @return entire payload length.
     */
    @Override
    protected int getPayloadLength() {
        return GENERIC_HEADER_LENGTH + mIkeEncryptedPayloadBody.getLength();
    }

    /**
     * Return the payload type as a String.
     *
     * @return the payload type as a String.
     */
    @Override
    public String getTypeString() {
        return "Encrypted and Authenticated Payload";
    }
}
