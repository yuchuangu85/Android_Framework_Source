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

package com.android.server.wifi.hotspot2.anqp;

import com.android.internal.annotations.VisibleForTesting;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * An object holding the raw octets of an ANQP element as provided by the wpa_supplicant.
 */
public class RawByteElement extends ANQPElement {
    private final byte[] mPayload;

    @VisibleForTesting
    public RawByteElement(Constants.ANQPElementType infoID, byte[] payload) {
        super(infoID);
        mPayload = payload;
    }

    /**
     * Parse a RawByteElement from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @return {@link HSConnectionCapabilityElement}
     */
    public static RawByteElement parse(Constants.ANQPElementType infoID, ByteBuffer payload) {
        byte[] rawBytes = new byte[payload.remaining()];
        if (payload.hasRemaining()) {
            payload.get(rawBytes);
        }
        return new RawByteElement(infoID, rawBytes);
    }

    public byte[] getPayload() {
        return mPayload;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof RawByteElement)) {
            return false;
        }
        RawByteElement that = (RawByteElement) thatObject;
        return getID() == that.getID() && Arrays.equals(mPayload, that.mPayload);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(mPayload);
    }
}
