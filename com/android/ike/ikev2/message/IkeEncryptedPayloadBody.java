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

import com.android.ike.ikev2.exceptions.IkeException;
import com.android.internal.annotations.VisibleForTesting;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/**
 * IkeEncryptedPayloadBody is a package private class that represents an IKE payload substructure
 * that contains initialization vector, encrypted content, padding, pad length and integrity
 * checksum.
 *
 * <p>Both an Encrypted Payload (IkeSkPayload) and an EncryptedFragmentPayload (IkeSkfPayload)
 * consists of an IkeEncryptedPayloadBody instance.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7296#page-105">RFC 7296, Internet Key Exchange
 *     Protocol Version 2 (IKEv2)</a>
 * @see <a href="https://tools.ietf.org/html/rfc7383#page-6">RFC 7383, Internet Key Exchange
 *     Protocol Version 2 (IKEv2) Message Fragmentation</a>
 */
final class IkeEncryptedPayloadBody {
    // Length of pad length field.
    private static final int PAD_LEN_LEN = 1;

    private final byte[] mUnencryptedData;
    private final byte[] mEncryptedAndPaddedData;
    private final byte[] mIv;
    private final byte[] mIntegrityChecksum;

    /**
     * Package private constructor for constructing an instance of IkeEncryptedPayloadBody from
     * decrypting an incoming packet.
     */
    IkeEncryptedPayloadBody(
            byte[] message, Mac integrityMac, int checksumLen, Cipher decryptCipher, SecretKey dKey)
            throws IkeException, GeneralSecurityException {
        ByteBuffer inputBuffer = ByteBuffer.wrap(message);

        // Skip IKE header and SK payload header
        byte[] tempArray = new byte[IkeHeader.IKE_HEADER_LENGTH + IkePayload.GENERIC_HEADER_LENGTH];
        inputBuffer.get(tempArray);

        // Extract bytes for authentication and decryption.
        int expectedIvLen = decryptCipher.getBlockSize();
        mIv = new byte[expectedIvLen];

        int encryptedDataLen =
                message.length
                        - (IkeHeader.IKE_HEADER_LENGTH
                                + IkePayload.GENERIC_HEADER_LENGTH
                                + expectedIvLen
                                + checksumLen);
        // IkeMessage will catch exception if encryptedDataLen is negative.
        mEncryptedAndPaddedData = new byte[encryptedDataLen];

        mIntegrityChecksum = new byte[checksumLen];
        inputBuffer.get(mIv).get(mEncryptedAndPaddedData).get(mIntegrityChecksum);

        // Authenticate and decrypt.
        byte[] dataToAuthenticate = Arrays.copyOfRange(message, 0, message.length - checksumLen);
        validateChecksumOrThrow(dataToAuthenticate, integrityMac, mIntegrityChecksum);
        mUnencryptedData = decrypt(mEncryptedAndPaddedData, decryptCipher, dKey, mIv);
    }

    /**
     * Package private constructor for constructing an instance of IkeEncryptedPayloadBody for
     * building an outbound packet.
     */
    IkeEncryptedPayloadBody(
            IkeHeader ikeHeader,
            @IkePayload.PayloadType int firstPayloadType,
            byte[] unencryptedPayloads,
            Mac integrityMac,
            int checksumLen,
            Cipher encryptCipher,
            SecretKey eKey) {
        this(
                ikeHeader,
                firstPayloadType,
                unencryptedPayloads,
                integrityMac,
                checksumLen,
                encryptCipher,
                eKey,
                encryptCipher.getIV(),
                calculatePadding(unencryptedPayloads.length, encryptCipher.getBlockSize()));
    }

    /** Package private constructor only for testing. */
    @VisibleForTesting
    IkeEncryptedPayloadBody(
            IkeHeader ikeHeader,
            @IkePayload.PayloadType int firstPayloadType,
            byte[] unencryptedPayloads,
            Mac integrityMac,
            int checksumLen,
            Cipher encryptCipher,
            SecretKey eKey,
            byte[] iv,
            byte[] padding) {
        mUnencryptedData = unencryptedPayloads;

        // Encrypt data
        mIv = iv;
        mEncryptedAndPaddedData = encrypt(unencryptedPayloads, encryptCipher, eKey, iv, padding);

        // Build authenticated section using ByteBuffer. Authenticated section includes bytes from
        // beginning of IKE header to the pad length, which are concatenation of IKE header, current
        // payload header, iv and encrypted and padded data.
        int dataToAuthenticateLength =
                IkeHeader.IKE_HEADER_LENGTH
                        + IkePayload.GENERIC_HEADER_LENGTH
                        + iv.length
                        + mEncryptedAndPaddedData.length;
        ByteBuffer authenticatedSectionBuffer = ByteBuffer.allocate(dataToAuthenticateLength);

        // Encode IKE header
        int encryptedPayloadLength =
                IkePayload.GENERIC_HEADER_LENGTH
                        + iv.length
                        + mEncryptedAndPaddedData.length
                        + checksumLen;
        ikeHeader.encodeToByteBuffer(authenticatedSectionBuffer, encryptedPayloadLength);

        // Encode payload header. The next payload type field indicates the first payload nested in
        // this SkPayload/SkfPayload.
        int payloadLength =
                IkePayload.GENERIC_HEADER_LENGTH
                        + iv.length
                        + mEncryptedAndPaddedData.length
                        + checksumLen;
        IkePayload.encodePayloadHeaderToByteBuffer(
                firstPayloadType, payloadLength, authenticatedSectionBuffer);

        // Encode iv and padded encrypted data.
        authenticatedSectionBuffer.put(iv).put(mEncryptedAndPaddedData);

        // Calculate checksum
        mIntegrityChecksum =
                calculateChecksum(authenticatedSectionBuffer.array(), integrityMac, checksumLen);
    }

    // TODO: Add another constructor for AEAD protected payload.

    // TODO: Add constructors that initiate IkeEncryptedPayloadBody for an outbound packet

    /** Package private for testing */
    @VisibleForTesting
    static byte[] calculateChecksum(byte[] dataToAuthenticate, Mac integrityMac, int checksumLen) {
        ByteBuffer inputBuffer = ByteBuffer.wrap(dataToAuthenticate);
        integrityMac.update(inputBuffer);
        byte[] calculatedChecksum = Arrays.copyOfRange(integrityMac.doFinal(), 0, checksumLen);
        return calculatedChecksum;
    }

    /** Package private for testing */
    @VisibleForTesting
    static void validateChecksumOrThrow(
            byte[] dataToAuthenticate, Mac integrityMac, byte[] integrityChecksum)
            throws GeneralSecurityException {
        // TODO: Make it package private and add test.
        int checkSumLen = integrityChecksum.length;
        byte[] calculatedChecksum =
                calculateChecksum(dataToAuthenticate, integrityMac, checkSumLen);

        if (!Arrays.equals(integrityChecksum, calculatedChecksum)) {
            throw new GeneralSecurityException("Message authentication failed.");
        }
    }

    /** Package private for testing */
    @VisibleForTesting
    static byte[] encrypt(
            byte[] dataToEncrypt, Cipher encryptCipher, SecretKey eKey, byte[] iv, byte[] padding) {
        int padLength = padding.length;
        int paddedDataLength = dataToEncrypt.length + padLength + PAD_LEN_LEN;
        ByteBuffer inputBuffer = ByteBuffer.allocate(paddedDataLength);
        inputBuffer.put(dataToEncrypt).put(padding).put((byte) padLength);
        inputBuffer.rewind();

        try {
            // Encrypt data.
            ByteBuffer outputBuffer = ByteBuffer.allocate(paddedDataLength);
            encryptCipher.init(Cipher.ENCRYPT_MODE, eKey, new IvParameterSpec(iv));
            encryptCipher.doFinal(inputBuffer, outputBuffer);
            return outputBuffer.array();
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Fail to encrypt IKE message. ", e);
        }
    }

    /** Package private for testing */
    @VisibleForTesting
    static byte[] decrypt(byte[] encryptedData, Cipher decryptCipher, SecretKey dKey, byte[] iv)
            throws GeneralSecurityException {
        // TODO: Make it package private and add test.
        decryptCipher.init(Cipher.DECRYPT_MODE, dKey, new IvParameterSpec(iv));

        ByteBuffer inputBuffer = ByteBuffer.wrap(encryptedData);
        ByteBuffer outputBuffer = ByteBuffer.allocate(encryptedData.length);
        decryptCipher.doFinal(inputBuffer, outputBuffer);

        // Remove padding
        outputBuffer.rewind();
        int padLength = Byte.toUnsignedInt(outputBuffer.get(encryptedData.length - PAD_LEN_LEN));
        byte[] decryptedData = new byte[encryptedData.length - padLength - PAD_LEN_LEN];

        outputBuffer.get(decryptedData);
        return decryptedData;
    }

    /** Package private for testing */
    @VisibleForTesting
    static byte[] calculatePadding(int dataToEncryptLength, int blockSize) {
        // Sum of dataToEncryptLength, PAD_LEN_LEN and padLength should be aligned with block size.
        int unpaddedLen = dataToEncryptLength + PAD_LEN_LEN;
        int padLength = (unpaddedLen + blockSize - 1) / blockSize * blockSize - unpaddedLen;
        byte[] padding = new byte[padLength];

        // According to RFC 7296, "Padding MAY contain any value".
        new SecureRandom().nextBytes(padding);

        return padding;
    }

    /** Package private */
    byte[] getUnencryptedData() {
        return mUnencryptedData;
    }

    /** Package private */
    int getLength() {
        return (mIv.length + mEncryptedAndPaddedData.length + mIntegrityChecksum.length);
    }

    /** Package private */
    byte[] encode() {
        ByteBuffer buffer = ByteBuffer.allocate(getLength());
        buffer.put(mIv).put(mEncryptedAndPaddedData).put(mIntegrityChecksum);
        return buffer.array();
    }
}
