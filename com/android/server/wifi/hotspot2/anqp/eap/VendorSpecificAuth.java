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

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The Vendor Specific authentication parameter, IEEE802.11-2012, table 8-188.
 *
 * Format:
 * | Data |
 * variable
 */
public class VendorSpecificAuth extends AuthParam {
    private final byte[] mData;

    @VisibleForTesting
    public VendorSpecificAuth(byte[] data) {
        super(AuthParam.PARAM_TYPE_VENDOR_SPECIFIC);
        mData = data;
    }

    /**
     * Parse a VendorSpecificAuth from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @param length The length of the data
     * @return {@link VendorSpecificAuth}
     * @throws BufferUnderflowException
     */
    public static VendorSpecificAuth parse(ByteBuffer payload, int length) {
        byte[] data = new byte[length];
        payload.get(data);
        return new VendorSpecificAuth(data);
    }

    public byte[] getData() {
        return mData;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (thatObject == this) {
            return true;
        }
        if (!(thatObject instanceof VendorSpecificAuth)) {
            return false;
        }
        VendorSpecificAuth that = (VendorSpecificAuth) thatObject;
        return Arrays.equals(mData, that.mData);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(mData);
    }

    @Override
    public String toString() {
        return "VendorSpecificAuth{mData=" + Arrays.toString(mData) + "}";
    }
}
