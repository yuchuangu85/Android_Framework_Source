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

package com.android.server.wifi;

import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Class for storing an IMSI (International Mobile Subscriber Identity) parameter.  The IMSI
 * contains number (up to 15) of numerical digits.  When an IMSI ends with a '*', the specified
 * IMSI is a prefix.
 */
public class IMSIParameter {
    /**
     * Per 2.2 of 3GPP TS 23.003
     * MCC (Mobile Country Code) is a 3 digit number and MNC (Mobile Network Code) is a 2
     * or 3 digit number;
     * The max length of IMSI is 15;
     */
    public static final int MCC_MNC_LENGTH_5 = 5;
    public static final int MCC_MNC_LENGTH_6 = 6;
    private static final int MAX_IMSI_LENGTH = 15;

    private final String mImsi;
    private final boolean mPrefix;

    @VisibleForTesting
    public IMSIParameter(String imsi, boolean prefix) {
        mImsi = imsi;
        mPrefix = prefix;
    }

    /**
     * Build an IMSIParameter object from the given string.  A null will be returned for a
     * malformed string.
     *
     * @param imsi The IMSI string
     * @return {@link IMSIParameter}
     */
    public static IMSIParameter build(String imsi) {
        if (TextUtils.isEmpty(imsi)) {
            return null;
        }
        if (imsi.length() > MAX_IMSI_LENGTH) {
            return null;
        }

        // Detect the first non-digit character.
        int nonDigitIndex;
        char stopChar = '\0';
        for (nonDigitIndex = 0; nonDigitIndex < imsi.length(); nonDigitIndex++) {
            stopChar = imsi.charAt(nonDigitIndex);
            if (stopChar < '0' || stopChar > '9') {
                break;
            }
        }

        if (nonDigitIndex == imsi.length()) {
            // Full IMSI.
            return new IMSIParameter(imsi, false);
        } else if (nonDigitIndex == imsi.length() - 1 && stopChar == '*'
                && (nonDigitIndex == MCC_MNC_LENGTH_5 || nonDigitIndex == MCC_MNC_LENGTH_6)) {
            // IMSI prefix.
            return new IMSIParameter(imsi.substring(0, nonDigitIndex), true);
        }
        return null;
    }

    /**
     * Perform matching against the given full IMSI.
     *
     * @param fullIMSI The full IMSI to match against
     * @return true if matched
     */
    public boolean matchesImsi(String fullIMSI) {
        if (fullIMSI == null) {
            return false;
        }

        if (mPrefix) {
            // Prefix matching.
            return mImsi.regionMatches(false, 0, fullIMSI, 0, mImsi.length());
        } else {
            // Exact matching.
            return mImsi.equals(fullIMSI);
        }
    }

    /**
     * Perform matching against the given MCC-MNC (Mobile Country Code and Mobile Network
     * Code) combination.
     *
     * @param mccMnc The MCC-MNC to match against
     * @return true if matched
     */
    public boolean matchesMccMnc(String mccMnc) {
        if (mccMnc == null) {
            return false;
        }
        if (mccMnc.length() != MCC_MNC_LENGTH_5 && mccMnc.length() != MCC_MNC_LENGTH_6) {
            return false;
        }
        if (mPrefix && mccMnc.length() != mImsi.length()) {
            return false;
        }

        return mImsi.startsWith(mccMnc);
    }

    /**
     * If the IMSI is full length.
     *
     * @return true If the length of IMSI is full, false otherwise.
     */
    public boolean isFullImsi() {
        return !mPrefix;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof IMSIParameter)) {
            return false;
        }

        IMSIParameter that = (IMSIParameter) thatObject;
        return mPrefix == that.mPrefix && TextUtils.equals(mImsi, that.mImsi);
    }

    @Override
    public int hashCode() {
        int result = mImsi != null ? mImsi.hashCode() : 0;
        result = 31 * result + (mPrefix ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        if (mPrefix) {
            return mImsi + '*';
        }
        else {
            return mImsi;
        }
    }
}
