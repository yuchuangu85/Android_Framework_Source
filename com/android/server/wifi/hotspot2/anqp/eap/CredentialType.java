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

package com.android.server.wifi.hotspot2.anqp.eap;

import com.android.internal.annotations.VisibleForTesting;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * The Credential Type authentication parameter, IEEE802.11-2012, table 8-188.
 * Used by both Credential Type and Tunneled EAP Method Credential Type authentication
 * parameter.
 *
 * Format:
 * | Type |
 *    1
 */
public class CredentialType extends AuthParam {
    public static final int CREDENTIAL_TYPE_SIM = 1;
    public static final int CREDENTIAL_TYPE_USIM = 2;
    public static final int CREDENTIAL_TYPE_NFC = 3;
    public static final int CREDENTIAL_TYPE_HARDWARE_TOKEN = 4;
    public static final int CREDENTIAL_TYPE_SOFTWARE_TOKEN = 5;
    public static final int CREDENTIAL_TYPE_CERTIFICATE = 6;
    public static final int CREDENTIAL_TYPE_USERNAME_PASSWORD = 7;
    public static final int CREDENTIAL_TYPE_NONE = 8;
    public static final int CREDENTIAL_TYPE_ANONYMOUS = 9;
    public static final int CREDENTIAL_TYPE_VENDOR_SPECIFIC = 10;

    @VisibleForTesting
    public static final int EXPECTED_LENGTH_VALUE = 1;

    private final int mType;

    @VisibleForTesting
    public CredentialType(int authType, int credType) {
        super(authType);
        mType = credType;
    }

    /**
     * Parse a CredentialType from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @param length The length of the data
     * @param tunneled Flag indicating if this is for a Tunneled EAP Method
     * @return {@link CredentialType}
     * @throws ProtocolException
     * @throws BufferUnderflowException
     */
    public static CredentialType parse(ByteBuffer payload, int length, boolean tunneled)
            throws ProtocolException {
        if (length != EXPECTED_LENGTH_VALUE) {
            throw new ProtocolException("Invalid length: " + length);
        }
        int credType = payload.get() & 0xFF;
        int authType = tunneled ? AuthParam.PARAM_TYPE_TUNNELED_EAP_METHOD_CREDENTIAL_TYPE
                : AuthParam.PARAM_TYPE_CREDENTIAL_TYPE;
        return new CredentialType(authType, credType);
    }

    public int getType() {
        return mType;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (thatObject == this) {
            return true;
        }
        if (!(thatObject instanceof CredentialType)) {
            return false;
        }
        CredentialType that = (CredentialType) thatObject;
        return mType == that.mType;
    }

    @Override
    public int hashCode() {
        return mType;
    }

    @Override
    public String toString() {
        return "CredentialType{mType=" + mType + "}";
    }
}
