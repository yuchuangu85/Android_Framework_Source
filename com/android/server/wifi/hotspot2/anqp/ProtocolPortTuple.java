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
import com.android.server.wifi.ByteBufferReader;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The ProtoPort Tuple used by Connection Capability vendor specific ANQP Element,
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.5
 *
 * Format:
 * | IP Procotol | Port Number | Status |
 *        1             2           1
 */
public class ProtocolPortTuple {
    /**
     * Number of raw bytes needed for the tuple.
     */
    @VisibleForTesting
    public static final int RAW_BYTE_SIZE = 4;

    public static final int PROTO_STATUS_CLOSED = 0;
    public static final int PROTO_STATUS_OPEN = 1;
    public static final int PROTO_STATUS_UNKNOWN = 2;

    private final int mProtocol;
    private final int mPort;
    private final int mStatus;

    @VisibleForTesting
    public ProtocolPortTuple(int protocol, int port, int status) {
        mProtocol = protocol;
        mPort = port;
        mStatus = status;
    }

    /**
     * Parse a ProtocolPortTuple from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @return {@link ProtocolPortTuple}
     * @throws BufferUnderflowException
     */
    public static ProtocolPortTuple parse(ByteBuffer payload) {
        int protocol = payload.get();
        int port = (int) ByteBufferReader.readInteger(payload, ByteOrder.LITTLE_ENDIAN, 2)
                & 0xFFFF;
        int status = payload.get() & 0xFF;
        return new ProtocolPortTuple(protocol, port, status);
    }

    public int getProtocol() {
        return mProtocol;
    }

    public int getPort() {
        return mPort;
    }

    public int getStatus() {
        return mStatus;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof ProtocolPortTuple)) {
            return false;
        }
        ProtocolPortTuple that = (ProtocolPortTuple) thatObject;
        return mProtocol == that.mProtocol
                && mPort == that.mPort
                && mStatus == that.mStatus;
    }

    @Override
    public int hashCode() {
        return (mProtocol * 31 + mPort) * 31 + mStatus;
    }

    @Override
    public String toString() {
        return "ProtocolTuple{" + "mProtocol=" + mProtocol + ", mPort=" + mPort
                + ", mStatus=" + mStatus + '}';
    }
}
