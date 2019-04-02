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
import com.android.server.wifi.ByteBufferReader;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The Expanded EAP Method authentication parameter, IEEE802.11-2012, table 8-189.
 * Used by both Expanded EAP Method and Expanded Inner EAP Method.
 *
 * Format:
 * | Vendor ID | Vendor Type |
 *       3            4
 */
public class ExpandedEAPMethod extends AuthParam {
    public static final int EXPECTED_LENGTH_VALUE = 7;

    private final int mVendorID;
    private final long mVendorType;

    @VisibleForTesting
    public ExpandedEAPMethod(int authType, int vendorID, long vendorType) {
        super(authType);
        mVendorID = vendorID;
        mVendorType = vendorType;
    }

    /**
     * Parse a ExpandedEAPMethod from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @param length The length of the data
     * @param inner Flag indicating if this is for an Inner EAP method
     * @return {@link ExpandedEAPMethod}
     * @throws ProtocolException
     * @throws BufferUnderflowException
     */
    public static ExpandedEAPMethod parse(ByteBuffer payload, int length, boolean inner)
            throws ProtocolException {
        if (length != EXPECTED_LENGTH_VALUE) {
            throw new ProtocolException("Invalid length value: " + length);
        }

        // Vendor ID and Vendor Type are expressed in big-endian byte order according to
        // the spec.
        int vendorID = (int) ByteBufferReader.readInteger(payload, ByteOrder.BIG_ENDIAN, 3)
                & 0xFFFFFF;
        long vendorType = ByteBufferReader.readInteger(payload, ByteOrder.BIG_ENDIAN, 4)
                & 0xFFFFFFFF;

        int authType = inner ? AuthParam.PARAM_TYPE_EXPANDED_INNER_EAP_METHOD
                : AuthParam.PARAM_TYPE_EXPANDED_EAP_METHOD;
        return new ExpandedEAPMethod(authType, vendorID, vendorType);
    }

    public int getVendorID() {
        return mVendorID;
    }

    public long getVendorType() {
        return mVendorType;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (thatObject == this) {
            return true;
        }
        if (!(thatObject instanceof ExpandedEAPMethod)) {
            return false;
        }
        ExpandedEAPMethod that = (ExpandedEAPMethod) thatObject;
        return mVendorID == that.mVendorID && mVendorType == that.mVendorType;
    }

    @Override
    public int hashCode() {
        return (mVendorID) * 31 + (int) mVendorType;
    }

    @Override
    public String toString() {
        return "ExpandedEAPMethod{mVendorID=" + mVendorID + " mVendorType=" + mVendorType + "}";
    }
}
