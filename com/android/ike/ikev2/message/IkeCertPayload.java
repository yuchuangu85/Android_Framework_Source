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

import com.android.ike.ikev2.exceptions.AuthenticationFailedException;
import com.android.ike.ikev2.exceptions.IkeException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;

/**
 * IkeCertPayload is an abstract class that represents the common information for all Certificate
 * Payload carrying different types of certifciate-related data and static methods related to
 * certificate validation.
 *
 * <p>Certificate Payload is only sent in IKE_AUTH exchange.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.6">RFC 7296, Internet Key Exchange
 *     Protocol Version 2 (IKEv2)</a>
 */
public abstract class IkeCertPayload extends IkePayload {
    // Length of certificate encoding type field in octets.
    private static final int CERT_ENCODING_LEN = 1;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        CERTIFICATE_ENCODING_X509_CERT_SIGNATURE,
        CERTIFICATE_ENCODING_CRL,
        CERTIFICATE_ENCODING_X509_CERT_HASH_URL,
    })
    public @interface CertificateEncoding {}

    public static final int CERTIFICATE_ENCODING_X509_CERT_SIGNATURE = 4;
    public static final int CERTIFICATE_ENCODING_CRL = 7;
    public static final int CERTIFICATE_ENCODING_X509_CERT_HASH_URL = 12;

    @CertificateEncoding public final int certEncodingType;

    protected IkeCertPayload(boolean critical, @CertificateEncoding int encodingType) {
        super(PAYLOAD_TYPE_CERT, critical);
        certEncodingType = encodingType;
    }

    protected static IkeCertPayload getIkeCertPayload(boolean critical, byte[] payloadBody)
            throws IkeException {
        ByteBuffer inputBuffer = ByteBuffer.wrap(payloadBody);

        int certEncodingType = Byte.toUnsignedInt(inputBuffer.get());
        byte[] certData = new byte[payloadBody.length - CERT_ENCODING_LEN];
        inputBuffer.get(certData);
        switch (certEncodingType) {
            case CERTIFICATE_ENCODING_X509_CERT_SIGNATURE:
                return new IkeCertX509CertPayload(critical, certData);
                // TODO: Support decoding CRL and "Hash and URL".
            case CERTIFICATE_ENCODING_CRL:
                throw new AuthenticationFailedException(
                        "CERTIFICATE_ENCODING_CRL decoding is unsupported.");
            case CERTIFICATE_ENCODING_X509_CERT_HASH_URL:
                throw new AuthenticationFailedException(
                        "CERTIFICATE_ENCODING_X509_CERT_HASH_URL decoding is unsupported");
            default:
                throw new AuthenticationFailedException("Unrecognized certificate encoding type.");
        }
    }
}
