/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.os.Parcelable;
import android.os.Parcel;

import java.util.BitSet;

import java.nio.ByteBuffer;

import java.util.Date;

/**
 * Describes information about a detected access point. In addition
 * to the attributes described here, the supplicant keeps track of
 * {@code quality}, {@code noise}, and {@code maxbitrate} attributes,
 * but does not currently report them to external clients.
 */
public class WifiParser {

    public WifiParser() {}


    /*
     * {@hide}
     */
    class IE {
        int id;
        byte data[];
    }

    private static final int VENDOR_SPECIFIC_IE = 221;
    private static final int IEEE_RSN_IE = 48; //IEEE 2012 8.4.2.27


    private static final int WPA_IE_VENDOR_TYPE = 0x0050f201; //WFA WPA vendor IE OUI/type

    /*
     * parse beacon or probe response frame and build the capabilities
     * {@hide}
     *
     * This function is called so as to build the capabilities string of the scan result, hence it is called
     * by AutoJoin controller when handling scan results that are coming from WifiScanner.
     *
     * It parses the ieee beacon's capability field, WPA and RSNE IE as per spec, but build the
     * ScanResult.capabilities String in a way that mirror the values returned by wpa_supplicant.
     *
     * Once the capabilities string is build, the ScanResult can be used be the system as if it was coming from supplicant.
     */
     /* @hide
     * */
    static public String parse_akm(IE full_IE[], BitSet ieee_cap) {
        boolean privacy = false;
        boolean error = false;
        if (ieee_cap == null)
            return null;

        if (full_IE == null)
            return null;

        privacy = ieee_cap.get(4);

        String capabilities = "";
        boolean rsne_found = false;
        boolean wpa_found = false;

        for (IE ie : full_IE) {
            String security = "";
            if (ie.id == IEEE_RSN_IE) {
                rsne_found = true;
                //parsing WPA2 capabilities

                ByteBuffer buf = ByteBuffer.wrap(ie.data);

                int total_len = ie.data.length;
                int offset = 2;

                //version
                if ((total_len - offset) < 2) {
                    //not enough space for version field
                    security = "";
                    error = true;
                    break;
                }
                int val = 0;
                if (0x0100 != buf.getShort(offset)) {
                    //incorrect version
                    security = "";
                    error = true;
                    break;
                }
                offset += 2;


                //group cipher
                if ((total_len - offset) < 4) {
                    security = ""; //parse error on group cipher suite
                    error = true;
                    break;
                }
                offset += 4; //skip the group cipher

                security = "[WPA2"; //found the RSNE IE, hence start building the capability string

                //pairwise cipher
                if ((total_len - offset) < 2) {
                    security = ""; //parse error no pairwise cipher
                    error = true;
                    break;
                }
                val = buf.getShort(offset);
                if ((total_len - offset) < (2 + val * 4)) {
                    security = ""; //parse error no pairwise cipher
                    error = true;
                    break;
                }
                offset += 2 + val * 4; //skip the pairwise ciphers

                //AKM
                if ((total_len - offset) < 2) {
                    security = ""; //parse error no AKM
                    error = true;
                    break;
                }
                val = buf.getShort(offset);
                if ((total_len - offset) < (2 + val * 4)) {
                    security = ""; //parse error no pairwise cipher
                    error = true;
                    break;
                }
                offset += 2;
                if (val == 0) {
                    security += "-EAP"; //default AKM
                }
                for (int i = 0; i < val; i++) {
                    int akm = buf.getInt(offset);
                    boolean found = false;
                    switch (akm) {
                        case 0x01ac0f00:
                            security += found ? "+" : "-" + "EAP";
                            found = true;
                            break;
                        case 0x02ac0f00:
                            security += found ? "+" : "-" + "PSK"; //PSK as 802.11-2012 11.6.1.2
                            found = true;
                            break;
                        case 0x03ac0f00:
                            security += found ? "+" : "-" + "FT/EAP";
                            found = true;
                            break;
                        case 0x04ac0f00:
                            security += found ? "+" : "-" + "FT/PSK";
                            found = true;
                            break;
                        case 0x06ac0f00:
                            security += found ? "+" : "-" + "PSK-SHA256";
                            found = true;
                            break;
                        case 0x05ac0f00:
                            security += found ? "+" : "-" + "EAP-SHA256";
                            found = true;
                            break;
                    }
                    offset += 4;
                }
                //we parsed what we want at this point
                security += "]";
                capabilities += security;

            }

            if (ie.id == VENDOR_SPECIFIC_IE) {
                int total_len = ie.data.length;
                int offset = 2;

                //version
                if ((total_len - offset) < 4) {
                    //not enough space for OUI and type field
                    security = "";
                    error = true;
                    break;
                }

                ByteBuffer buf = ByteBuffer.wrap(ie.data);

                if (buf.getInt(offset) != 0x01F25000) {
                    //look for HS2.0 and WPA IE
                    security = "";
                    continue;
                }

                security = "[WPA"; //prep the string for WPA

                //version
                if ((total_len - offset) < 2) {
                    //not enough space for version field
                    security = "";
                    error = true;
                    break;
                }
                int val = 0;
                if (0x0100 != buf.getShort(offset)) {
                    //incorrect version
                    security = "";
                    error = true;
                    break;
                }
                offset += 2;


                //group cipher
                if ((total_len - offset) < 4) {
                    security = ""; //parse error on group cipher suite
                    error = true;
                    break;
                }
                offset += 4; //skip the group cipher


                //pairwise cipher
                if ((total_len - offset) < 2) {
                    security = ""; //parse error no pairwise cipher
                    error = true;
                    break;
                }
                val = buf.getShort(offset);
                if ((total_len - offset) < (2 + val * 4)) {
                    security = ""; //parse error no pairwise cipher
                    error = true;
                    break;
                }
                offset += 2 + val * 4; //skip the pairwise ciphers

                //AKM
                if ((total_len - offset) < 2) {
                    security = ""; //parse error no AKM
                    error = true;
                    break;
                }
                val = buf.getShort(offset);
                if ((total_len - offset) < (2 + val * 4)) {
                    security = ""; //parse error no pairwise cipher
                    error = true;
                    break;
                }
                offset += 2;
                if (val == 0) {
                    security += "-EAP"; //default AKM
                }
                for (int i = 0; i < val; i++) {
                    int akm = buf.getInt(offset);
                    boolean found = false;
                    switch (akm) {
                        case 0x01f25000:
                            security += found ? "+" : "-" + "EAP";
                            found = true;
                            break;
                        case 0x02f25000:
                            security += found ? "+" : "-" + "PSK"; //PSK as 802.11-2012 11.6.1.2
                            found = true;
                            break;

                    }
                    offset += 4;
                }
                //we parsed what we want at this point
                security += "]";
            }
        }

        if (rsne_found == false && wpa_found == false && privacy) {
            //private Beacon without an RSNE or WPA IE, hence WEP0
            capabilities += "[WEP]";
        }

        if (error)
            return null;
        else
            return capabilities;
    }


}
