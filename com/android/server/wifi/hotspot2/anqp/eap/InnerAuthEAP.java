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
 * The Inner Authentication EAP Method Type authentication parameter, IEEE802.11-2012, table 8-188.
 *
 * Format:
 * | EAP Method ID |
 *         1
 */
public class InnerAuthEAP extends AuthParam {
    @VisibleForTesting
    public static final int EXPECTED_LENGTH_VALUE = 1;

    private final int mEAPMethodID;

    @VisibleForTesting
    public InnerAuthEAP(int eapMethodID) {
        super(AuthParam.PARAM_TYPE_INNER_AUTH_EAP_METHOD_TYPE);
        mEAPMethodID = eapMethodID;
    }

    /**
     * Parse a InnerAuthEAP from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @param length The length of the data
     * @return {@link InnerAuthEAP}
     * @throws ProtocolException
     * @throws BufferUnderflowException
     */
    public static InnerAuthEAP parse(ByteBuffer payload, int length) throws ProtocolException {
        if (length != EXPECTED_LENGTH_VALUE) {
            throw new ProtocolException("Invalid length: " + length);
        }
        int eapMethodID = payload.get() & 0xFF;
        return new InnerAuthEAP(eapMethodID);
    }

    public int getEAPMethodID() {
        return mEAPMethodID;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (thatObject == this) {
            return true;
        }
        if (!(thatObject instanceof InnerAuthEAP)) {
            return false;
        }
        InnerAuthEAP that = (InnerAuthEAP) thatObject;
        return mEAPMethodID == that.mEAPMethodID;
    }

    @Override
    public int hashCode() {
        return mEAPMethodID;
    }

    @Override
    public String toString() {
        return "InnerAuthEAP{mEAPMethodID=" + mEAPMethodID + "}";
    }
}
