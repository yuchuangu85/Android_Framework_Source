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

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The WAN Metrics vendor specific ANQP Element,
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.4
 *
 * Format:
 * | WAN Info | Downlink Speed | Uplink Speed | Downlink Load | Uplink Load | LMD |
 *      1             4               4               1              1         2
 *
 * WAN Info Format:
 * | Link Status | Symmetric Link | At Capacity | Reserved |
 *      B0 B1            B2             B3        B4 - B7
 */
public class HSWanMetricsElement extends ANQPElement {
    public static final int LINK_STATUS_RESERVED = 0;
    public static final int LINK_STATUS_UP = 1;
    public static final int LINK_STATUS_DOWN = 2;
    public static final int LINK_STATUS_TEST = 3;

    @VisibleForTesting
    public static final int EXPECTED_BUFFER_SIZE = 13;

    @VisibleForTesting
    public static final int LINK_STATUS_MASK = (1 << 0 | 1 << 1);

    @VisibleForTesting
    public static final int SYMMETRIC_LINK_MASK = 1 << 2;

    @VisibleForTesting
    public static final int AT_CAPACITY_MASK = 1 << 3;

    private static final int MAX_LOAD = 256;

    private final int mStatus;
    private final boolean mSymmetric;
    private final boolean mCapped;
    private final long mDownlinkSpeed;
    private final long mUplinkSpeed;
    private final int mDownlinkLoad;
    private final int mUplinkLoad;
    private final int mLMD;     // Load Measurement Duration.

    @VisibleForTesting
    public HSWanMetricsElement(int status, boolean symmetric, boolean capped, long downlinkSpeed,
            long uplinkSpeed, int downlinkLoad, int uplinkLoad, int lmd) {
        super(Constants.ANQPElementType.HSWANMetrics);
        mStatus = status;
        mSymmetric = symmetric;
        mCapped = capped;
        mDownlinkSpeed = downlinkSpeed;
        mUplinkSpeed = uplinkSpeed;
        mDownlinkLoad = downlinkLoad;
        mUplinkLoad = uplinkLoad;
        mLMD = lmd;
    }

    /**
     * Parse a HSWanMetricsElement from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @return {@link HSWanMetricsElement}
     * @throws ProtocolException
     */
    public static HSWanMetricsElement parse(ByteBuffer payload)
            throws ProtocolException {
        if (payload.remaining() != EXPECTED_BUFFER_SIZE) {
            throw new ProtocolException("Unexpected buffer size: " + payload.remaining());
        }

        int wanInfo = payload.get() & 0xFF;
        int status = wanInfo & LINK_STATUS_MASK;
        boolean symmetric = (wanInfo & SYMMETRIC_LINK_MASK) != 0;
        boolean capped = (wanInfo & AT_CAPACITY_MASK) != 0;
        long downlinkSpeed = ByteBufferReader.readInteger(payload, ByteOrder.LITTLE_ENDIAN, 4)
                & 0xFFFFFFFFL;
        long uplinkSpeed = ByteBufferReader.readInteger(payload, ByteOrder.LITTLE_ENDIAN, 4)
                & 0xFFFFFFFFL;
        int downlinkLoad = payload.get() & 0xFF;
        int uplinkLoad = payload.get() & 0xFF;
        int lmd = (int) ByteBufferReader.readInteger(payload, ByteOrder.LITTLE_ENDIAN, 2) & 0xFFFF;
        return new HSWanMetricsElement(status, symmetric, capped, downlinkSpeed, uplinkSpeed,
                downlinkLoad, uplinkLoad, lmd);
    }

    public int getStatus() {
        return mStatus;
    }

    public boolean isSymmetric() {
        return mSymmetric;
    }

    public boolean isCapped() {
        return mCapped;
    }

    public long getDownlinkSpeed() {
        return mDownlinkSpeed;
    }

    public long getUplinkSpeed() {
        return mUplinkSpeed;
    }

    public int getDownlinkLoad() {
        return mDownlinkLoad;
    }

    public int getUplinkLoad() {
        return mUplinkLoad;
    }

    public int getLMD() {
        return mLMD;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof HSWanMetricsElement)) {
            return false;
        }
        HSWanMetricsElement that = (HSWanMetricsElement) thatObject;
        return mStatus == that.mStatus
                && mSymmetric == that.mSymmetric
                && mCapped == that.mCapped
                && mDownlinkSpeed == that.mDownlinkSpeed
                && mUplinkSpeed == that.mUplinkSpeed
                && mDownlinkLoad == that.mDownlinkLoad
                && mUplinkLoad == that.mUplinkLoad
                && mLMD == that.mLMD;
    }

    @Override
    public int hashCode() {
        return (int) (mStatus + mDownlinkSpeed + mUplinkSpeed + mDownlinkLoad
                + mUplinkLoad + mLMD);
    }

    @Override
    public String toString() {
        return String.format("HSWanMetrics{mStatus=%s, mSymmetric=%s, mCapped=%s, " +
                "mDlSpeed=%d, mUlSpeed=%d, mDlLoad=%f, mUlLoad=%f, mLMD=%d}",
                mStatus, mSymmetric, mCapped,
                mDownlinkSpeed, mUplinkSpeed,
                mDownlinkLoad * 100.0 / MAX_LOAD,
                mUplinkLoad * 100.0 / MAX_LOAD,
                mLMD);
    }
}
