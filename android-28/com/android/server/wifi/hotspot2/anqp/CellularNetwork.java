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

import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The IEI (Information Element Identity) contained in the Generic Container for the
 * 3GPP Cellular Network ANQP element.
 *
 * Refer to Annex A of 3GPP TS 24.234 version 11.3.0 for information on the data format:
 * (http://www.etsi.org/deliver/etsi_ts/124200_124299/124234/11.03.00_60/ts_124234v110300p.pdf)
 */
public class CellularNetwork {
    private static final String TAG = "CellularNetwork";

    /**
     * IEI type for PLMN (Public Land Mobile Network) list.
     */
    @VisibleForTesting
    public static final int IEI_TYPE_PLMN_LIST = 0;

    @VisibleForTesting
    public static final int IEI_CONTENT_LENGTH_MASK = 0x7F;

    /**
     * Number of bytes for each PLMN (Public Land Mobile Network).
     */
    @VisibleForTesting
    public static final int PLMN_DATA_BYTES = 3;

    /**
     * The value for comparing the third digit of MNC data with to determine if the MNC is
     * two or three digits.
     */
    private static final int MNC_2DIGIT_VALUE = 0xF;

    /**
     * List of PLMN (Public Land Mobile Network) information.
     */
    private final List<String> mPlmnList;

    @VisibleForTesting
    public CellularNetwork(List<String> plmnList) {
        mPlmnList = plmnList;
    }

    /**
     * Parse a CellularNetwork from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @return {@link CellularNetwork}
     * @throws ProtocolException
     * @throws BufferUnderflowException
     */
    public static CellularNetwork parse(ByteBuffer payload) throws ProtocolException {
        int ieiType = payload.get() & 0xFF;
        int ieiSize = payload.get() & IEI_CONTENT_LENGTH_MASK;

        // Skip this IEI if it is an unsupported type.
        if (ieiType != IEI_TYPE_PLMN_LIST) {
            Log.e(TAG, "Ignore unsupported IEI Type: " + ieiType);
            // Advance the buffer position to the next IEI.
            payload.position(payload.position() + ieiSize);
            return null;
        }

        // Get PLMN count.
        int plmnCount = payload.get() & 0xFF;

        // Verify IEI size with PLMN count.  The IEI size contained the PLMN count field plus
        // the bytes for the PLMNs.
        if (ieiSize != (plmnCount * PLMN_DATA_BYTES + 1)) {
            throw new ProtocolException("IEI size and PLMN count mismatched: IEI Size=" + ieiSize
                    + " PLMN Count=" + plmnCount);
        }

        // Process each PLMN.
        List<String> plmnList = new ArrayList<>();
        while (plmnCount > 0) {
            plmnList.add(parsePlmn(payload));
            plmnCount--;
        }
        return new CellularNetwork(plmnList);
    }

    public List<String> getPlmns() {
        return Collections.unmodifiableList(mPlmnList);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof CellularNetwork)) {
            return false;
        }
        CellularNetwork that = (CellularNetwork) thatObject;
        return mPlmnList.equals(that.mPlmnList);
    }

    @Override
    public int hashCode() {
        return mPlmnList.hashCode();
    }

    @Override
    public String toString() {
        return "CellularNetwork{mPlmnList=" + mPlmnList + "}";
    }

    /**
     * Parse the PLMN information from the given buffer.  A string representing a hex value
     * of |MCC|MNC| will be returned.
     *
     * PLMN Coding Format:
     * b7                         b0
     * | MCC Digit 2 | MCC Digit 1 |
     * | MNC Digit 3 | MCC Digit 3 |
     * | MNC Digit 2 | MNC Digit 1 |
     *
     * @param payload The buffer to read from.
     * @return {@Link String}
     * @throws BufferUnderflowException
     */
    private static String parsePlmn(ByteBuffer payload) {
        byte[] plmn = new byte[PLMN_DATA_BYTES];
        payload.get(plmn);

        // Formatted as | MCC Digit 1 | MCC Digit 2 | MCC Digit 3 |
        int mcc = ((plmn[0] << 8) & 0xF00) | (plmn[0] & 0x0F0) | (plmn[1] & 0x00F);

        // Formated as |MNC Digit 1 | MNC Digit 2 |
        int mnc = ((plmn[2] << 4) & 0xF0) | ((plmn[2] >> 4) & 0x0F);

        // The digit 3 of MNC decides if the MNC is 2 or 3 digits number.  When it is equal to
        // 0xF, MNC is a 2 digit value. Otherwise, it is a 3 digit number.
        int mncDigit3 = (plmn[1] >> 4) & 0x0F;
        return (mncDigit3 != MNC_2DIGIT_VALUE)
                ? String.format("%03x%03x", mcc, (mnc << 4) | mncDigit3)
                : String.format("%03x%02x", mcc, mnc);
    }
}
