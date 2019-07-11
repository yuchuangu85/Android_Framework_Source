/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.server.wifi.util;

import android.net.wifi.ScanResult;
import android.net.wifi.ScanResult.InformationElement;
import android.util.Log;

import com.android.server.wifi.ByteBufferReader;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.anqp.Constants;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.BitSet;

public class InformationElementUtil {
    private static final String TAG = "InformationElementUtil";

    public static InformationElement[] parseInformationElements(byte[] bytes) {
        if (bytes == null) {
            return new InformationElement[0];
        }
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        ArrayList<InformationElement> infoElements = new ArrayList<>();
        boolean found_ssid = false;
        while (data.remaining() > 1) {
            int eid = data.get() & Constants.BYTE_MASK;
            int elementLength = data.get() & Constants.BYTE_MASK;

            if (elementLength > data.remaining() || (eid == InformationElement.EID_SSID
                    && found_ssid)) {
                // APs often pad the data with bytes that happen to match that of the EID_SSID
                // marker.  This is not due to a known issue for APs to incorrectly send the SSID
                // name multiple times.
                break;
            }
            if (eid == InformationElement.EID_SSID) {
                found_ssid = true;
            }

            InformationElement ie = new InformationElement();
            ie.id = eid;
            ie.bytes = new byte[elementLength];
            data.get(ie.bytes);
            infoElements.add(ie);
        }
        return infoElements.toArray(new InformationElement[infoElements.size()]);
    }

    /**
     * Parse and retrieve the Roaming Consortium Information Element from the list of IEs.
     *
     * @param ies List of IEs to retrieve from
     * @return {@link RoamingConsortium}
     */
    public static RoamingConsortium getRoamingConsortiumIE(InformationElement[] ies) {
        RoamingConsortium roamingConsortium = new RoamingConsortium();
        if (ies != null) {
            for (InformationElement ie : ies) {
                if (ie.id == InformationElement.EID_ROAMING_CONSORTIUM) {
                    try {
                        roamingConsortium.from(ie);
                    } catch (RuntimeException e) {
                        Log.e(TAG, "Failed to parse Roaming Consortium IE: " + e.getMessage());
                    }
                }
            }
        }
        return roamingConsortium;
    }

    /**
     * Parse and retrieve the Hotspot 2.0 Vendor Specific Information Element from the list of IEs.
     *
     * @param ies List of IEs to retrieve from
     * @return {@link Vsa}
     */
    public static Vsa getHS2VendorSpecificIE(InformationElement[] ies) {
        Vsa vsa = new Vsa();
        if (ies != null) {
            for (InformationElement ie : ies) {
                if (ie.id == InformationElement.EID_VSA) {
                    try {
                        vsa.from(ie);
                    } catch (RuntimeException e) {
                        Log.e(TAG, "Failed to parse Vendor Specific IE: " + e.getMessage());
                    }
                }
            }
        }
        return vsa;
    }

    /**
     * Parse and retrieve the Interworking information element from the list of IEs.
     *
     * @param ies List of IEs to retrieve from
     * @return {@link Interworking}
     */
    public static Interworking getInterworkingIE(InformationElement[] ies) {
        Interworking interworking = new Interworking();
        if (ies != null) {
            for (InformationElement ie : ies) {
                if (ie.id == InformationElement.EID_INTERWORKING) {
                    try {
                        interworking.from(ie);
                    } catch (RuntimeException e) {
                        Log.e(TAG, "Failed to parse Interworking IE: " + e.getMessage());
                    }
                }
            }
        }
        return interworking;
    }

    public static class BssLoad {
        public int stationCount = 0;
        public int channelUtilization = 0;
        public int capacity = 0;

        public void from(InformationElement ie) {
            if (ie.id != InformationElement.EID_BSS_LOAD) {
                throw new IllegalArgumentException("Element id is not BSS_LOAD, : " + ie.id);
            }
            if (ie.bytes.length != 5) {
                throw new IllegalArgumentException("BSS Load element length is not 5: "
                                                   + ie.bytes.length);
            }
            ByteBuffer data = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);
            stationCount = data.getShort() & Constants.SHORT_MASK;
            channelUtilization = data.get() & Constants.BYTE_MASK;
            capacity = data.getShort() & Constants.SHORT_MASK;
        }
    }

    public static class HtOperation {
        public int secondChannelOffset = 0;

        public int getChannelWidth() {
            if (secondChannelOffset != 0) {
                return 1;
            } else {
                return 0;
            }
        }

        public int getCenterFreq0(int primaryFrequency) {
            //40 MHz
            if (secondChannelOffset != 0) {
                if (secondChannelOffset == 1) {
                    return primaryFrequency + 10;
                } else if (secondChannelOffset == 3) {
                    return primaryFrequency - 10;
                } else {
                    Log.e("HtOperation", "Error on secondChannelOffset: " + secondChannelOffset);
                    return 0;
                }
            } else {
                return 0;
            }
        }

        public void from(InformationElement ie) {
            if (ie.id != InformationElement.EID_HT_OPERATION) {
                throw new IllegalArgumentException("Element id is not HT_OPERATION, : " + ie.id);
            }
            secondChannelOffset = ie.bytes[1] & 0x3;
        }
    }

    public static class VhtOperation {
        public int channelMode = 0;
        public int centerFreqIndex1 = 0;
        public int centerFreqIndex2 = 0;

        public boolean isValid() {
            return channelMode != 0;
        }

        public int getChannelWidth() {
            return channelMode + 1;
        }

        public int getCenterFreq0() {
            //convert channel index to frequency in MHz, channel 36 is 5180MHz
            return (centerFreqIndex1 - 36) * 5 + 5180;
        }

        public int getCenterFreq1() {
            if (channelMode > 1) { //160MHz
                return (centerFreqIndex2 - 36) * 5 + 5180;
            } else {
                return 0;
            }
        }

        public void from(InformationElement ie) {
            if (ie.id != InformationElement.EID_VHT_OPERATION) {
                throw new IllegalArgumentException("Element id is not VHT_OPERATION, : " + ie.id);
            }
            channelMode = ie.bytes[0] & Constants.BYTE_MASK;
            centerFreqIndex1 = ie.bytes[1] & Constants.BYTE_MASK;
            centerFreqIndex2 = ie.bytes[2] & Constants.BYTE_MASK;
        }
    }

    public static class Interworking {
        public NetworkDetail.Ant ant = null;
        public boolean internet = false;
        public long hessid = 0L;

        public void from(InformationElement ie) {
            if (ie.id != InformationElement.EID_INTERWORKING) {
                throw new IllegalArgumentException("Element id is not INTERWORKING, : " + ie.id);
            }
            ByteBuffer data = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);
            int anOptions = data.get() & Constants.BYTE_MASK;
            ant = NetworkDetail.Ant.values()[anOptions & 0x0f];
            internet = (anOptions & 0x10) != 0;
            // There are only three possible lengths for the Interworking IE:
            // Len 1: Access Network Options only
            // Len 3: Access Network Options & Venue Info
            // Len 7: Access Network Options & HESSID
            // Len 9: Access Network Options, Venue Info, & HESSID
            if (ie.bytes.length != 1
                    && ie.bytes.length != 3
                    && ie.bytes.length != 7
                    && ie.bytes.length != 9) {
                throw new IllegalArgumentException(
                        "Bad Interworking element length: " + ie.bytes.length);
            }

            if (ie.bytes.length == 7 || ie.bytes.length == 9) {
                hessid = ByteBufferReader.readInteger(data, ByteOrder.BIG_ENDIAN, 6);
            }
        }
    }

    public static class RoamingConsortium {
        public int anqpOICount = 0;
        public long[] roamingConsortiums = null;

        public void from(InformationElement ie) {
            if (ie.id != InformationElement.EID_ROAMING_CONSORTIUM) {
                throw new IllegalArgumentException("Element id is not ROAMING_CONSORTIUM, : "
                        + ie.id);
            }
            ByteBuffer data = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);
            anqpOICount = data.get() & Constants.BYTE_MASK;

            int oi12Length = data.get() & Constants.BYTE_MASK;
            int oi1Length = oi12Length & Constants.NIBBLE_MASK;
            int oi2Length = (oi12Length >>> 4) & Constants.NIBBLE_MASK;
            int oi3Length = ie.bytes.length - 2 - oi1Length - oi2Length;
            int oiCount = 0;
            if (oi1Length > 0) {
                oiCount++;
                if (oi2Length > 0) {
                    oiCount++;
                    if (oi3Length > 0) {
                        oiCount++;
                    }
                }
            }
            roamingConsortiums = new long[oiCount];
            if (oi1Length > 0 && roamingConsortiums.length > 0) {
                roamingConsortiums[0] =
                        ByteBufferReader.readInteger(data, ByteOrder.BIG_ENDIAN, oi1Length);
            }
            if (oi2Length > 0 && roamingConsortiums.length > 1) {
                roamingConsortiums[1] =
                        ByteBufferReader.readInteger(data, ByteOrder.BIG_ENDIAN, oi2Length);
            }
            if (oi3Length > 0 && roamingConsortiums.length > 2) {
                roamingConsortiums[2] =
                        ByteBufferReader.readInteger(data, ByteOrder.BIG_ENDIAN, oi3Length);
            }
        }
    }

    public static class Vsa {
        private static final int ANQP_DOMID_BIT = 0x04;

        public NetworkDetail.HSRelease hsRelease = null;
        public int anqpDomainID = 0;    // No domain ID treated the same as a 0; unique info per AP.

        public void from(InformationElement ie) {
            ByteBuffer data = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (ie.bytes.length >= 5 && data.getInt() == Constants.HS20_FRAME_PREFIX) {
                int hsConf = data.get() & Constants.BYTE_MASK;
                switch ((hsConf >> 4) & Constants.NIBBLE_MASK) {
                    case 0:
                        hsRelease = NetworkDetail.HSRelease.R1;
                        break;
                    case 1:
                        hsRelease = NetworkDetail.HSRelease.R2;
                        break;
                    default:
                        hsRelease = NetworkDetail.HSRelease.Unknown;
                        break;
                }
                if ((hsConf & ANQP_DOMID_BIT) != 0) {
                    if (ie.bytes.length < 7) {
                        throw new IllegalArgumentException(
                                "HS20 indication element too short: " + ie.bytes.length);
                    }
                    anqpDomainID = data.getShort() & Constants.SHORT_MASK;
                }
            }
        }
    }

    /**
     * This IE contained a bit field indicating the capabilities being advertised by the STA.
     * The size of the bit field (number of bytes) is indicated by the |Length| field in the IE.
     *
     * Refer to Section 8.4.2.29 in IEEE 802.11-2012 Spec for capability associated with each
     * bit.
     *
     * Here is the wire format of this IE:
     * | Element ID | Length | Capabilities |
     *       1           1          n
     */
    public static class ExtendedCapabilities {
        private static final int RTT_RESP_ENABLE_BIT = 70;
        private static final int SSID_UTF8_BIT = 48;

        public BitSet capabilitiesBitSet;

        /**
         * @return true if SSID should be interpreted using UTF-8 encoding
         */
        public boolean isStrictUtf8() {
            return capabilitiesBitSet.get(SSID_UTF8_BIT);
        }

        /**
         * @return true if 802.11 MC RTT Response is enabled
         */
        public boolean is80211McRTTResponder() {
            return capabilitiesBitSet.get(RTT_RESP_ENABLE_BIT);
        }

        public ExtendedCapabilities() {
            capabilitiesBitSet = new BitSet();
        }

        public ExtendedCapabilities(ExtendedCapabilities other) {
            capabilitiesBitSet = other.capabilitiesBitSet;
        }

        /**
         * Parse an ExtendedCapabilities from the IE containing raw bytes.
         *
         * @param ie The Information element data
         */
        public void from(InformationElement ie) {
            capabilitiesBitSet = BitSet.valueOf(ie.bytes);
        }
    }

    /**
     * parse beacon to build the capabilities
     *
     * This class is used to build the capabilities string of the scan results coming
     * from HAL. It parses the ieee beacon's capability field, WPA and RSNE IE as per spec,
     * and builds the ScanResult.capabilities String in a way that mirrors the values returned
     * by wpa_supplicant.
     */
    public static class Capabilities {
        private static final int CAP_ESS_BIT_OFFSET = 0;
        private static final int CAP_PRIVACY_BIT_OFFSET = 4;

        private static final int WPA_VENDOR_OUI_TYPE_ONE = 0x01f25000;
        private static final int WPS_VENDOR_OUI_TYPE = 0x04f25000;
        private static final short WPA_VENDOR_OUI_VERSION = 0x0001;
        private static final short RSNE_VERSION = 0x0001;

        private static final int WPA_AKM_EAP = 0x01f25000;
        private static final int WPA_AKM_PSK = 0x02f25000;

        private static final int WPA2_AKM_EAP = 0x01ac0f00;
        private static final int WPA2_AKM_PSK = 0x02ac0f00;
        private static final int WPA2_AKM_FT_EAP = 0x03ac0f00;
        private static final int WPA2_AKM_FT_PSK = 0x04ac0f00;
        private static final int WPA2_AKM_EAP_SHA256 = 0x05ac0f00;
        private static final int WPA2_AKM_PSK_SHA256 = 0x06ac0f00;

        private static final int WPA_CIPHER_NONE = 0x00f25000;
        private static final int WPA_CIPHER_TKIP = 0x02f25000;
        private static final int WPA_CIPHER_CCMP = 0x04f25000;

        private static final int RSN_CIPHER_NONE = 0x00ac0f00;
        private static final int RSN_CIPHER_TKIP = 0x02ac0f00;
        private static final int RSN_CIPHER_CCMP = 0x04ac0f00;
        private static final int RSN_CIPHER_NO_GROUP_ADDRESSED = 0x07ac0f00;

        public ArrayList<Integer> protocol;
        public ArrayList<ArrayList<Integer>> keyManagement;
        public ArrayList<ArrayList<Integer>> pairwiseCipher;
        public ArrayList<Integer> groupCipher;
        public boolean isESS;
        public boolean isPrivacy;
        public boolean isWPS;

        public Capabilities() {
        }

        // RSNE format (size unit: byte)
        //
        // | Element ID | Length | Version | Group Data Cipher Suite |
        //      1           1         2                 4
        // | Pairwise Cipher Suite Count | Pairwise Cipher Suite List |
        //              2                            4 * m
        // | AKM Suite Count | AKM Suite List | RSN Capabilities |
        //          2               4 * n               2
        // | PMKID Count | PMKID List | Group Management Cipher Suite |
        //        2          16 * s                 4
        //
        // Note: InformationElement.bytes has 'Element ID' and 'Length'
        //       stripped off already
        private void parseRsnElement(InformationElement ie) {
            ByteBuffer buf = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);

            try {
                // version
                if (buf.getShort() != RSNE_VERSION) {
                    // incorrect version
                    return;
                }

                // found the RSNE IE, hence start building the capability string
                protocol.add(ScanResult.PROTOCOL_WPA2);

                // group data cipher suite
                groupCipher.add(parseRsnCipher(buf.getInt()));

                // pairwise cipher suite count
                short cipherCount = buf.getShort();
                ArrayList<Integer> rsnPairwiseCipher = new ArrayList<>();
                // pairwise cipher suite list
                for (int i = 0; i < cipherCount; i++) {
                    rsnPairwiseCipher.add(parseRsnCipher(buf.getInt()));
                }
                pairwiseCipher.add(rsnPairwiseCipher);

                // AKM
                // AKM suite count
                short akmCount = buf.getShort();
                ArrayList<Integer> rsnKeyManagement = new ArrayList<>();

                for (int i = 0; i < akmCount; i++) {
                    int akm = buf.getInt();
                    switch (akm) {
                        case WPA2_AKM_EAP:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_EAP);
                            break;
                        case WPA2_AKM_PSK:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_PSK);
                            break;
                        case WPA2_AKM_FT_EAP:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_FT_EAP);
                            break;
                        case WPA2_AKM_FT_PSK:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_FT_PSK);
                            break;
                        case WPA2_AKM_EAP_SHA256:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_EAP_SHA256);
                            break;
                        case WPA2_AKM_PSK_SHA256:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_PSK_SHA256);
                            break;
                        default:
                            // do nothing
                            break;
                    }
                }
                // Default AKM
                if (rsnKeyManagement.isEmpty()) {
                    rsnKeyManagement.add(ScanResult.KEY_MGMT_EAP);
                }
                keyManagement.add(rsnKeyManagement);
            } catch (BufferUnderflowException e) {
                Log.e("IE_Capabilities", "Couldn't parse RSNE, buffer underflow");
            }
        }

        private static int parseWpaCipher(int cipher) {
            switch (cipher) {
                case WPA_CIPHER_NONE:
                    return ScanResult.CIPHER_NONE;
                case WPA_CIPHER_TKIP:
                    return ScanResult.CIPHER_TKIP;
                case WPA_CIPHER_CCMP:
                    return ScanResult.CIPHER_CCMP;
                default:
                    Log.w("IE_Capabilities", "Unknown WPA cipher suite: "
                            + Integer.toHexString(cipher));
                    return ScanResult.CIPHER_NONE;
            }
        }

        private static int parseRsnCipher(int cipher) {
            switch (cipher) {
                case RSN_CIPHER_NONE:
                    return ScanResult.CIPHER_NONE;
                case RSN_CIPHER_TKIP:
                    return ScanResult.CIPHER_TKIP;
                case RSN_CIPHER_CCMP:
                    return ScanResult.CIPHER_CCMP;
                case RSN_CIPHER_NO_GROUP_ADDRESSED:
                    return ScanResult.CIPHER_NO_GROUP_ADDRESSED;
                default:
                    Log.w("IE_Capabilities", "Unknown RSN cipher suite: "
                            + Integer.toHexString(cipher));
                    return ScanResult.CIPHER_NONE;
            }
        }

        private static boolean isWpsElement(InformationElement ie) {
            ByteBuffer buf = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);
            try {
                // WPS OUI and type
                return (buf.getInt() == WPS_VENDOR_OUI_TYPE);
            } catch (BufferUnderflowException e) {
                Log.e("IE_Capabilities", "Couldn't parse VSA IE, buffer underflow");
                return false;
            }
        }

        private static boolean isWpaOneElement(InformationElement ie) {
            ByteBuffer buf = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);

            try {
                // WPA OUI and type
                return (buf.getInt() == WPA_VENDOR_OUI_TYPE_ONE);
            } catch (BufferUnderflowException e) {
                Log.e("IE_Capabilities", "Couldn't parse VSA IE, buffer underflow");
                return false;
            }
        }

        // WPA type 1 format (size unit: byte)
        //
        // | Element ID | Length | OUI | Type | Version |
        //      1           1       3     1        2
        // | Group Data Cipher Suite |
        //             4
        // | Pairwise Cipher Suite Count | Pairwise Cipher Suite List |
        //              2                            4 * m
        // | AKM Suite Count | AKM Suite List |
        //          2               4 * n
        //
        // Note: InformationElement.bytes has 'Element ID' and 'Length'
        //       stripped off already
        //
        private void parseWpaOneElement(InformationElement ie) {
            ByteBuffer buf = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);

            try {
                // skip WPA OUI and type parsing. isWpaOneElement() should have
                // been called for verification before we reach here.
                buf.getInt();

                // version
                if (buf.getShort() != WPA_VENDOR_OUI_VERSION)  {
                    // incorrect version
                    return;
                }

                // start building the string
                protocol.add(ScanResult.PROTOCOL_WPA);

                // group data cipher suite
                groupCipher.add(parseWpaCipher(buf.getInt()));

                // pairwise cipher suite count
                short cipherCount = buf.getShort();
                ArrayList<Integer> wpaPairwiseCipher = new ArrayList<>();
                // pairwise chipher suite list
                for (int i = 0; i < cipherCount; i++) {
                    wpaPairwiseCipher.add(parseWpaCipher(buf.getInt()));
                }
                pairwiseCipher.add(wpaPairwiseCipher);

                // AKM
                // AKM suite count
                short akmCount = buf.getShort();
                ArrayList<Integer> wpaKeyManagement = new ArrayList<>();

                // AKM suite list
                for (int i = 0; i < akmCount; i++) {
                    int akm = buf.getInt();
                    switch (akm) {
                        case WPA_AKM_EAP:
                            wpaKeyManagement.add(ScanResult.KEY_MGMT_EAP);
                            break;
                        case WPA_AKM_PSK:
                            wpaKeyManagement.add(ScanResult.KEY_MGMT_PSK);
                            break;
                        default:
                            // do nothing
                            break;
                    }
                }
                // Default AKM
                if (wpaKeyManagement.isEmpty()) {
                    wpaKeyManagement.add(ScanResult.KEY_MGMT_EAP);
                }
                keyManagement.add(wpaKeyManagement);
            } catch (BufferUnderflowException e) {
                Log.e("IE_Capabilities", "Couldn't parse type 1 WPA, buffer underflow");
            }
        }

        /**
         * Parse the Information Element and the 16-bit Capability Information field
         * to build the InformationElemmentUtil.capabilities object.
         *
         * @param ies -- Information Element array
         * @param beaconCap -- 16-bit Beacon Capability Information field
         */

        public void from(InformationElement[] ies, BitSet beaconCap) {
            protocol = new ArrayList<Integer>();
            keyManagement = new ArrayList<ArrayList<Integer>>();
            groupCipher = new ArrayList<Integer>();
            pairwiseCipher = new ArrayList<ArrayList<Integer>>();

            if (ies == null || beaconCap == null) {
                return;
            }
            isESS = beaconCap.get(CAP_ESS_BIT_OFFSET);
            isPrivacy = beaconCap.get(CAP_PRIVACY_BIT_OFFSET);
            for (InformationElement ie : ies) {
                if (ie.id == InformationElement.EID_RSN) {
                    parseRsnElement(ie);
                }

                if (ie.id == InformationElement.EID_VSA) {
                    if (isWpaOneElement(ie)) {
                        parseWpaOneElement(ie);
                    }
                    if (isWpsElement(ie)) {
                        // TODO(b/62134557): parse WPS IE to provide finer granularity information.
                        isWPS = true;
                    }
                }
            }
        }

        private String protocolToString(int protocol) {
            switch (protocol) {
                case ScanResult.PROTOCOL_NONE:
                    return "None";
                case ScanResult.PROTOCOL_WPA:
                    return "WPA";
                case ScanResult.PROTOCOL_WPA2:
                    return "WPA2";
                default:
                    return "?";
            }
        }

        private String keyManagementToString(int akm) {
            switch (akm) {
                case ScanResult.KEY_MGMT_NONE:
                    return "None";
                case ScanResult.KEY_MGMT_PSK:
                    return "PSK";
                case ScanResult.KEY_MGMT_EAP:
                    return "EAP";
                case ScanResult.KEY_MGMT_FT_EAP:
                    return "FT/EAP";
                case ScanResult.KEY_MGMT_FT_PSK:
                    return "FT/PSK";
                case ScanResult.KEY_MGMT_EAP_SHA256:
                    return "EAP-SHA256";
                case ScanResult.KEY_MGMT_PSK_SHA256:
                    return "PSK-SHA256";
                default:
                    return "?";
            }
        }

        private String cipherToString(int cipher) {
            switch (cipher) {
                case ScanResult.CIPHER_NONE:
                    return "None";
                case ScanResult.CIPHER_CCMP:
                    return "CCMP";
                case ScanResult.CIPHER_TKIP:
                    return "TKIP";
                default:
                    return "?";
            }
        }

        /**
         * Build the ScanResult.capabilities String.
         *
         * @return security string that mirrors what wpa_supplicant generates
         */
        public String generateCapabilitiesString() {
            String capabilities = "";
            // private Beacon without an RSNE or WPA IE, hence WEP0
            boolean isWEP = (protocol.isEmpty()) && isPrivacy;

            if (isWEP) {
                capabilities += "[WEP]";
            }
            for (int i = 0; i < protocol.size(); i++) {
                capabilities += "[" + protocolToString(protocol.get(i));
                if (i < keyManagement.size()) {
                    for (int j = 0; j < keyManagement.get(i).size(); j++) {
                        capabilities += ((j == 0) ? "-" : "+")
                                + keyManagementToString(keyManagement.get(i).get(j));
                    }
                }
                if (i < pairwiseCipher.size()) {
                    for (int j = 0; j < pairwiseCipher.get(i).size(); j++) {
                        capabilities += ((j == 0) ? "-" : "+")
                                + cipherToString(pairwiseCipher.get(i).get(j));
                    }
                }
                capabilities += "]";
            }
            if (isESS) {
                capabilities += "[ESS]";
            }
            if (isWPS) {
                capabilities += "[WPS]";
            }

            return capabilities;
        }
    }

    /**
     * Parser for the Traffic Indication Map (TIM) Information Element (EID 5). This element will
     * only be present in scan results that are derived from a Beacon Frame, not from the more
     * plentiful probe responses. Call 'isValid()' after parsing, to ensure the results are correct.
     */
    public static class TrafficIndicationMap {
        private static final int MAX_TIM_LENGTH = 254;
        private boolean mValid = false;
        public int mLength = 0;
        public int mDtimCount = -1;
        //Negative DTIM Period means no TIM element was given this frame.
        public int mDtimPeriod = -1;
        public int mBitmapControl = 0;

        /**
         * Is this a valid TIM information element.
         */
        public boolean isValid() {
            return mValid;
        }

        // Traffic Indication Map format (size unit: byte)
        //
        //| ElementID | Length | DTIM Count | DTIM Period | BitmapControl | Partial Virtual Bitmap |
        //      1          1          1            1               1                1 - 251
        //
        // Note: InformationElement.bytes has 'Element ID' and 'Length'
        //       stripped off already
        //
        public void from(InformationElement ie) {
            mValid = false;
            if (ie == null || ie.bytes == null) return;
            mLength = ie.bytes.length;
            ByteBuffer data = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);
            try {
                mDtimCount = data.get() & Constants.BYTE_MASK;
                mDtimPeriod = data.get() & Constants.BYTE_MASK;
                mBitmapControl = data.get() & Constants.BYTE_MASK;
                //A valid TIM element must have atleast one more byte
                data.get();
            } catch (BufferUnderflowException e) {
                return;
            }
            if (mLength <= MAX_TIM_LENGTH && mDtimPeriod > 0) {
                mValid = true;
            }
        }
    }

    /**
     * This util class determines the 802.11 standard (a/b/g/n/ac) being used
     */
    public static class WifiMode {
        public static final int MODE_UNDEFINED = 0; // Unknown/undefined
        public static final int MODE_11A = 1;       // 802.11a
        public static final int MODE_11B = 2;       // 802.11b
        public static final int MODE_11G = 3;       // 802.11g
        public static final int MODE_11N = 4;       // 802.11n
        public static final int MODE_11AC = 5;      // 802.11ac
        //<TODO> add support for 802.11ad and be more selective instead of defaulting to 11A

        /**
         * Use frequency, max supported rate, and the existence of VHT, HT & ERP fields in scan
         * scan result to determine the 802.11 Wifi standard being used.
         */
        public static int determineMode(int frequency, int maxRate, boolean foundVht,
                boolean foundHt, boolean foundErp) {
            if (foundVht) {
                return MODE_11AC;
            } else if (foundHt) {
                return MODE_11N;
            } else if (foundErp) {
                return MODE_11G;
            } else if (frequency < 3000) {
                if (maxRate < 24000000) {
                    return MODE_11B;
                } else {
                    return MODE_11G;
                }
            } else {
                return MODE_11A;
            }
        }

        /**
         * Map the wifiMode integer to its type, and output as String MODE_11<A/B/G/N/AC>
         */
        public static String toString(int mode) {
            switch(mode) {
                case MODE_11A:
                    return "MODE_11A";
                case MODE_11B:
                    return "MODE_11B";
                case MODE_11G:
                    return "MODE_11G";
                case MODE_11N:
                    return "MODE_11N";
                case MODE_11AC:
                    return "MODE_11AC";
                default:
                    return "MODE_UNDEFINED";
            }
        }
    }

    /**
     * Parser for both the Supported Rates & Extended Supported Rates Information Elements
     */
    public static class SupportedRates {
        public static final int MASK = 0x7F; // 0111 1111
        public boolean mValid = false;
        public ArrayList<Integer> mRates;

        public SupportedRates() {
            mRates = new ArrayList<Integer>();
        }

        /**
         * Is this a valid Supported Rates information element.
         */
        public boolean isValid() {
            return mValid;
        }

        /**
         * get the Rate in bits/s from associated byteval
         */
        public static int getRateFromByte(int byteVal) {
            byteVal &= MASK;
            switch(byteVal) {
                case 2:
                    return 1000000;
                case 4:
                    return 2000000;
                case 11:
                    return 5500000;
                case 12:
                    return 6000000;
                case 18:
                    return 9000000;
                case 22:
                    return 11000000;
                case 24:
                    return 12000000;
                case 36:
                    return 18000000;
                case 44:
                    return 22000000;
                case 48:
                    return 24000000;
                case 66:
                    return 33000000;
                case 72:
                    return 36000000;
                case 96:
                    return 48000000;
                case 108:
                    return 54000000;
                default:
                    //ERROR UNKNOWN RATE
                    return -1;
            }
        }

        // Supported Rates format (size unit: byte)
        //
        //| ElementID | Length | Supported Rates  [7 Little Endian Info bits - 1 Flag bit]
        //      1          1          1 - 8
        //
        // Note: InformationElement.bytes has 'Element ID' and 'Length'
        //       stripped off already
        //
        public void from(InformationElement ie) {
            mValid = false;
            if (ie == null || ie.bytes == null || ie.bytes.length > 8 || ie.bytes.length < 1)  {
                return;
            }
            ByteBuffer data = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);
            try {
                for (int i = 0; i < ie.bytes.length; i++) {
                    int rate = getRateFromByte(data.get());
                    if (rate > 0) {
                        mRates.add(rate);
                    } else {
                        return;
                    }
                }
            } catch (BufferUnderflowException e) {
                return;
            }
            mValid = true;
            return;
        }

        /**
         * Lists the rates in a human readable string
         */
        public String toString() {
            StringBuilder sbuf = new StringBuilder();
            for (Integer rate : mRates) {
                sbuf.append(String.format("%.1f", (double) rate / 1000000) + ", ");
            }
            return sbuf.toString();
        }
    }
}
