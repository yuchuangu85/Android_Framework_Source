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

import android.annotation.StringDef;

import com.android.ike.ikev2.exceptions.AuthenticationFailedException;
import com.android.ike.ikev2.exceptions.IkeException;
import com.android.ike.ikev2.message.IkeAuthPayload.AuthMethod;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * IkeAuthDigitalSignPayload represents Authentication Payload using a specific or generic digital
 * signature authentication method.
 *
 * <p>If AUTH_METHOD_RSA_DIGITAL_SIGN is used, then the hash algorithm is SHA1. If
 * AUTH_METHOD_GENERIC_DIGITAL_SIGN is used, the signature algorihtm and hash algorithm are
 * extracted from authentication data.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.8">RFC 7296, Internet Key Exchange
 *     Protocol Version 2 (IKEv2)</a>
 * @see <a href="https://tools.ietf.org/html/rfc7427">RFC 7427, Signature Authentication in the
 *     Internet Key Exchange Version 2 (IKEv2)</a>
 */
public class IkeAuthDigitalSignPayload extends IkeAuthPayload {

    // Byte arrays of DER encoded identifier ASN.1 objects that indicates the algorithm used to
    // generate the signature, extracted from
    // <a href="https://tools.ietf.org/html/rfc7427#appendix-A"> RFC 7427. There is no need to
    // understand the encoding process. They are just constants to indicate the algorithm type.
    private static final byte[] PKI_ALGO_ID_DER_BYTES_RSA_SHA1 = {
        (byte) 0x30, (byte) 0x0d, (byte) 0x06, (byte) 0x09,
        (byte) 0x2a, (byte) 0x86, (byte) 0x48, (byte) 0x86,
        (byte) 0xf7, (byte) 0x0d, (byte) 0x01, (byte) 0x01,
        (byte) 0x05, (byte) 0x05, (byte) 0x00
    };
    private static final byte[] PKI_ALGO_ID_DER_BYTES_RSA_SHA2_256 = {
        (byte) 0x30, (byte) 0x0d, (byte) 0x06, (byte) 0x09,
        (byte) 0x2a, (byte) 0x86, (byte) 0x48, (byte) 0x86,
        (byte) 0xf7, (byte) 0x0d, (byte) 0x01, (byte) 0x01,
        (byte) 0x0b, (byte) 0x05, (byte) 0x00
    };
    private static final byte[] PKI_ALGO_ID_DER_BYTES_RSA_SHA2_384 = {
        (byte) 0x30, (byte) 0x0d, (byte) 0x06, (byte) 0x09,
        (byte) 0x2a, (byte) 0x86, (byte) 0x48, (byte) 0x86,
        (byte) 0xf7, (byte) 0x0d, (byte) 0x01, (byte) 0x01,
        (byte) 0x0c, (byte) 0x05, (byte) 0x00
    };
    private static final byte[] PKI_ALGO_ID_DER_BYTES_RSA_SHA2_512 = {
        (byte) 0x30, (byte) 0x0d, (byte) 0x06, (byte) 0x09,
        (byte) 0x2a, (byte) 0x86, (byte) 0x48, (byte) 0x86,
        (byte) 0xf7, (byte) 0x0d, (byte) 0x01, (byte) 0x01,
        (byte) 0x0d, (byte) 0x05, (byte) 0x00
    };

    // Length of ASN.1 object length field.
    private static final int SIGNATURE_ALGO_ASN1_LEN_LEN = 1;

    // Currently we only support RSA for signature algorithm.
    @Retention(RetentionPolicy.SOURCE)
    @StringDef({
        SIGNATURE_ALGO_RSA_SHA1,
        SIGNATURE_ALGO_RSA_SHA2_256,
        SIGNATURE_ALGO_RSA_SHA2_384,
        SIGNATURE_ALGO_RSA_SHA2_512
    })
    public @interface SignatureAlgo {}

    public static final String SIGNATURE_ALGO_RSA_SHA1 = "SHA1withRSA";
    public static final String SIGNATURE_ALGO_RSA_SHA2_256 = "SHA256withRSA";
    public static final String SIGNATURE_ALGO_RSA_SHA2_384 = "SHA384withRSA";
    public static final String SIGNATURE_ALGO_RSA_SHA2_512 = "SHA512withRSA";
    // TODO: Allow users to configure authentication method using @SignatureAlgo

    public final String signatureAlgoAndHash;
    public final byte[] signature;

    protected IkeAuthDigitalSignPayload(
            boolean critical, @AuthMethod int authMethod, byte[] authData) throws IkeException {
        super(critical, authMethod);
        switch (authMethod) {
            case AUTH_METHOD_RSA_DIGITAL_SIGN:
                signatureAlgoAndHash = SIGNATURE_ALGO_RSA_SHA1;
                signature = authData;
                break;
            case AUTH_METHOD_GENERIC_DIGITAL_SIGN:
                ByteBuffer inputBuffer = ByteBuffer.wrap(authData);

                // Get signature algorithm.
                int signAlgoLen = Byte.toUnsignedInt(inputBuffer.get());
                byte[] signAlgoBytes = new byte[signAlgoLen];
                inputBuffer.get(signAlgoBytes);
                signatureAlgoAndHash = bytesToSignAlgoName(signAlgoBytes);

                // Get signature.
                signature = new byte[authData.length - SIGNATURE_ALGO_ASN1_LEN_LEN - signAlgoLen];
                inputBuffer.get(signature);
                break;
            default:
                // Won't hit here.
                throw new IllegalArgumentException("Unrecognized authentication method.");
        }
    }

    private String bytesToSignAlgoName(byte[] signAlgoBytes) throws AuthenticationFailedException {
        if (Arrays.equals(PKI_ALGO_ID_DER_BYTES_RSA_SHA1, signAlgoBytes)) {
            return SIGNATURE_ALGO_RSA_SHA1;
        } else if (Arrays.equals(PKI_ALGO_ID_DER_BYTES_RSA_SHA2_256, signAlgoBytes)) {
            return SIGNATURE_ALGO_RSA_SHA2_256;
        } else if (Arrays.equals(PKI_ALGO_ID_DER_BYTES_RSA_SHA2_384, signAlgoBytes)) {
            return SIGNATURE_ALGO_RSA_SHA2_384;
        } else if (Arrays.equals(PKI_ALGO_ID_DER_BYTES_RSA_SHA2_512, signAlgoBytes)) {
            return SIGNATURE_ALGO_RSA_SHA2_512;
        } else {
            throw new AuthenticationFailedException(
                    "Unrecognized ASN.1 objects for Signature algorithm and Hash");
        }
    }

    // TODO: Add methods for generating and validating signature.

    @Override
    protected void encodeAuthDataToByteBuffer(ByteBuffer byteBuffer) {
        // TODO: Implement it.
        throw new UnsupportedOperationException(
                "It is not supported to encode a " + getTypeString());
    }

    @Override
    protected int getAuthDataLength() {
        // TODO: Implement it.
        throw new UnsupportedOperationException(
                "It is not supported to get payload length of " + getTypeString());
    }

    @Override
    public String getTypeString() {
        return "Authentication-Digital-Signature Payload";
    }
}
