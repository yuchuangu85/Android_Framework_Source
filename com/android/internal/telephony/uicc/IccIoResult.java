/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.uicc;


/**
 * {@hide}
 */
public class
IccIoResult {

    private static final String UNKNOWN_ERROR = "unknown";

    private String getErrorString() {
        // Errors from 3gpp 11.11 9.4.1
        // Additional Errors from ETSI 102.221
        //
        // All error codes below are copied directly from their respective specification
        // without modification except in cases where necessary string formatting has been omitted.
        switch(sw1) {
            case 0x62:
                switch(sw2) {
                    case 0x00: return "No information given,"
                               + " state of non volatile memory unchanged";
                    case 0x81: return "Part of returned data may be corrupted";
                    case 0x82: return "End of file/record reached before reading Le bytes";
                    case 0x83: return "Selected file invalidated";
                    case 0x84: return "Selected file in termination state";
                    case 0xF1: return "More data available";
                    case 0xF2: return "More data available and proactive command pending";
                    case 0xF3: return "Response data available";
                }
                break;
            case 0x63:
                if (sw2 >> 4 == 0x0C) {
                    return "Command successful but after using an internal"
                        + "update retry routine but Verification failed";
                }
                switch(sw2) {
                    case 0xF1: return "More data expected";
                    case 0xF2: return "More data expected and proactive command pending";
                }
                break;
            case 0x64:
                switch(sw2) {
                    case 0x00: return "No information given,"
                               + " state of non-volatile memory unchanged";
                }
                break;
            case 0x65:
                switch(sw2) {
                    case 0x00: return "No information given, state of non-volatile memory changed";
                    case 0x81: return "Memory problem";
                }
                break;
            case 0x67:
                switch(sw2) {
                    case 0x00: return "incorrect parameter P3";
                    default: return "The interpretation of this status word is command dependent";
                }
                // break;
            case 0x6B: return "incorrect parameter P1 or P2";
            case 0x6D: return "unknown instruction code given in the command";
            case 0x6E: return "wrong instruction class given in the command";
            case 0x6F:
                switch(sw2) {
                    case 0x00: return "technical problem with no diagnostic given";
                    default: return "The interpretation of this status word is command dependent";
                }
                // break;
            case 0x68:
                switch(sw2) {
                    case 0x00: return "No information given";
                    case 0x81: return "Logical channel not supported";
                    case 0x82: return "Secure messaging not supported";
                }
                break;
            case 0x69:
                switch(sw2) {
                    case 0x00: return "No information given";
                    case 0x81: return "Command incompatible with file structure";
                    case 0x82: return "Security status not satisfied";
                    case 0x83: return "Authentication/PIN method blocked";
                    case 0x84: return "Referenced data invalidated";
                    case 0x85: return "Conditions of use not satisfied";
                    case 0x86: return "Command not allowed (no EF selected)";
                    case 0x89: return "Command not allowed - secure channel -"
                               + " security not satisfied";
                }
                break;
            case 0x6A:
                switch(sw2) {
                    case 0x80: return "Incorrect parameters in the data field";
                    case 0x81: return "Function not supported";
                    case 0x82: return "File not found";
                    case 0x83: return "Record not found";
                    case 0x84: return "Not enough memory space";
                    case 0x86: return "Incorrect parameters P1 to P2";
                    case 0x87: return "Lc inconsistent with P1 to P2";
                    case 0x88: return "Referenced data not found";
                }
                break;
            case 0x90: return null; // success
            case 0x91: return null; // success
            //Status Code 0x92 has contradictory meanings from 11.11 and 102.221 10.2.1.1
            case 0x92:
                if (sw2 >> 4 == 0) {
                    return "command successful but after using an internal update retry routine";
                }
                switch(sw2) {
                    case 0x40: return "memory problem";
                }
                break;
            case 0x93:
                switch(sw2) {
                    case 0x00:
                        return "SIM Application Toolkit is busy. Command cannot be executed"
                            + " at present, further normal commands are allowed.";
                }
                break;
            case 0x94:
                switch(sw2) {
                    case 0x00: return "no EF selected";
                    case 0x02: return "out f range (invalid address)";
                    case 0x04: return "file ID not found/pattern not found";
                    case 0x08: return "file is inconsistent with the command";
                }
                break;
            case 0x98:
                switch(sw2) {
                    case 0x02: return "no CHV initialized";
                    case 0x04: return "access condition not fulfilled/"
                            + "unsuccessful CHV verification, at least one attempt left/"
                            + "unsuccessful UNBLOCK CHV verification, at least one attempt left/"
                            + "authentication failed";
                    case 0x08: return "in contradiction with CHV status";
                    case 0x10: return "in contradiction with invalidation status";
                    case 0x40: return "unsuccessful CHV verification, no attempt left/"
                            + "unsuccessful UNBLOCK CHV verification, no attempt left/"
                            + "CHV blocked"
                            + "UNBLOCK CHV blocked";
                    case 0x50: return "increase cannot be performed, Max value reached";
                }
                break;
            case 0x9E: return null; // success
            case 0x9F: return null; // success
        }
        return UNKNOWN_ERROR;
    }


    public int sw1;
    public int sw2;

    public byte[] payload;

    public IccIoResult(int sw1, int sw2, byte[] payload) {
        this.sw1 = sw1;
        this.sw2 = sw2;
        this.payload = payload;
    }

    public IccIoResult(int sw1, int sw2, String hexString) {
        this(sw1, sw2, IccUtils.hexStringToBytes(hexString));
    }

    @Override
    public String toString() {
        return "IccIoResult sw1:0x" + Integer.toHexString(sw1) + " sw2:0x"
                + Integer.toHexString(sw2) + ((!success()) ? " Error: " + getErrorString() : "");
    }

    /**
     * true if this operation was successful
     * See GSM 11.11 Section 9.4
     * (the fun stuff is absent in 51.011)
     */
    public boolean success() {
        return sw1 == 0x90 || sw1 == 0x91 || sw1 == 0x9e || sw1 == 0x9f;
    }

    /**
     * Returns exception on error or null if success
     */
    public IccException getException() {
        if (success()) return null;

        switch (sw1) {
            case 0x94:
                if (sw2 == 0x08) {
                    return new IccFileTypeMismatch();
                } else {
                    return new IccFileNotFound();
                }
            default:
                return new IccException("sw1:" + sw1 + " sw2:" + sw2);
        }
    }
}
