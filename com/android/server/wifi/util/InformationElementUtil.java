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
import android.net.wifi.WifiAnnotations.Cipher;
import android.net.wifi.WifiAnnotations.KeyMgmt;
import android.net.wifi.WifiAnnotations.Protocol;
import android.net.wifi.WifiScanner;
import android.net.wifi.nl80211.NativeScanResult;
import android.net.wifi.nl80211.WifiNl80211Manager;
import android.util.Log;

import com.android.server.wifi.ByteBufferReader;
import com.android.server.wifi.MboOceConstants;
import com.android.server.wifi.hotspot2.NetworkDetail;
import com.android.server.wifi.hotspot2.anqp.Constants;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public class InformationElementUtil {
    private static final String TAG = "InformationElementUtil";
    private static final boolean DBG = false;
    public static InformationElement[] parseInformationElements(byte[] bytes) {
        if (bytes == null) {
            return new InformationElement[0];
        }
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        ArrayList<InformationElement> infoElements = new ArrayList<>();
        boolean found_ssid = false;
        while (data.remaining() > 1) {
            int eid = data.get() & Constants.BYTE_MASK;
            int eidExt = 0;
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
            } else if (eid == InformationElement.EID_EXTENSION_PRESENT) {
                if (elementLength == 0) {
                    // Malformed IE, skipping
                    break;
                }
                eidExt = data.get() & Constants.BYTE_MASK;
                elementLength--;
            }

            InformationElement ie = new InformationElement();
            ie.id = eid;
            ie.idExt = eidExt;
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
        public static final int INVALID = -1;
        public static final int MAX_CHANNEL_UTILIZATION = 255;
        public static final int MIN_CHANNEL_UTILIZATION = 0;
        public static final int CHANNEL_UTILIZATION_SCALE = 256;
        public int stationCount = INVALID;
        public int channelUtilization = INVALID;
        public int capacity = INVALID;

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
        private static final int HT_OPERATION_IE_LEN = 22;
        private boolean mPresent = false;
        private int mSecondChannelOffset = 0;

        /**
         * returns if HT Operation IE present in the message.
         */
        public boolean isPresent() {
            return mPresent;
        }

        /**
         * Returns channel width if it is 20 or 40MHz
         * Results will be invalid if channel width greater than 40MHz
         * So caller should only call this method if VHT Operation IE is not present,
         * or if VhtOperation.getChannelWidth() returns ScanResult.UNSPECIFIED.
         */
        public int getChannelWidth() {
            if (mSecondChannelOffset != 0) {
                return ScanResult.CHANNEL_WIDTH_40MHZ;
            } else {
                return ScanResult.CHANNEL_WIDTH_20MHZ;
            }
        }

        /**
         * Returns channel Center frequency (for 20/40 MHz channels only)
         * Results will be invalid for larger channel width,
         * So, caller should only call this method if VHT Operation IE is not present,
         * or if VhtOperation.getChannelWidth() returns ScanResult.UNSPECIFIED.
         */
        public int getCenterFreq0(int primaryFrequency) {
            if (mSecondChannelOffset != 0) {
                //40 MHz
                if (mSecondChannelOffset == 1) {
                    return primaryFrequency + 10;
                } else if (mSecondChannelOffset == 3) {
                    return primaryFrequency - 10;
                } else {
                    Log.e("HtOperation", "Error on secondChannelOffset: " + mSecondChannelOffset);
                    return 0;
                }
            } else {
                //20 MHz
                return primaryFrequency;
            }
        }

        /**
         * Parse the HT Operation IE to read the fields of interest.
         */
        public void from(InformationElement ie) {
            if (ie.id != InformationElement.EID_HT_OPERATION) {
                throw new IllegalArgumentException("Element id is not HT_OPERATION, : " + ie.id);
            }
            if (ie.bytes.length < HT_OPERATION_IE_LEN) {
                throw new IllegalArgumentException("Invalid HT_OPERATION len: " + ie.bytes.length);
            }
            mPresent = true;
            mSecondChannelOffset = ie.bytes[1] & 0x3;
        }
    }

    public static class VhtOperation {
        private static final int VHT_OPERATION_IE_LEN = 5;
        private boolean mPresent = false;
        private int mChannelMode = 0;
        private int mCenterFreqIndex1 = 0;
        private int mCenterFreqIndex2 = 0;

        /**
         * returns if VHT Operation IE present in the message.
         */
        public boolean isPresent() {
            return mPresent;
        }

        /**
         * Returns channel width if it is above 40MHz,
         * otherwise, returns {@link ScanResult.UNSPECIFIED} to indicate that
         * channel width should be obtained from the HT Operation IE via
         * HtOperation.getChannelWidth().
         */
        public int getChannelWidth() {
            if (mChannelMode == 0) {
                // 20 or 40MHz
                return ScanResult.UNSPECIFIED;
            } else if (mCenterFreqIndex2 == 0) {
                // No secondary channel
                return ScanResult.CHANNEL_WIDTH_80MHZ;
            } else if (Math.abs(mCenterFreqIndex2 - mCenterFreqIndex1) == 8) {
                // Primary and secondary channels adjacent
                return ScanResult.CHANNEL_WIDTH_160MHZ;
            } else {
                // Primary and secondary channels not adjacent
                return ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ;
            }
        }

        /**
         * Returns center frequency of primary channel (if channel width greater than 40MHz),
         * otherwise, it returns zero to indicate that center frequency should be obtained from
         * the HT Operation IE via HtOperation.getCenterFreq0().
         */
        public int getCenterFreq0() {
            if (mCenterFreqIndex1 == 0 || mChannelMode == 0) {
                return 0;
            } else {
                return ScanResult.convertChannelToFrequencyMhz(mCenterFreqIndex1,
                        WifiScanner.WIFI_BAND_5_GHZ);
            }
        }

         /**
         * Returns center frequency of secondary channel if exists (channel width greater than
         * 40MHz), otherwise, it returns zero.
         * Note that the secondary channel center frequency only applies to 80+80 or 160 MHz
         * channels.
         */
        public int getCenterFreq1() {
            if (mCenterFreqIndex2 == 0 || mChannelMode == 0) {
                return 0;
            } else {
                return ScanResult.convertChannelToFrequencyMhz(mCenterFreqIndex2,
                        WifiScanner.WIFI_BAND_5_GHZ);
            }
        }

        /**
         * Parse the VHT Operation IE to read the fields of interest.
         */
        public void from(InformationElement ie) {
            if (ie.id != InformationElement.EID_VHT_OPERATION) {
                throw new IllegalArgumentException("Element id is not VHT_OPERATION, : " + ie.id);
            }
            if (ie.bytes.length < VHT_OPERATION_IE_LEN) {
                throw new IllegalArgumentException("Invalid VHT_OPERATION len: " + ie.bytes.length);
            }
            mPresent = true;
            mChannelMode = ie.bytes[0] & Constants.BYTE_MASK;
            mCenterFreqIndex1 = ie.bytes[1] & Constants.BYTE_MASK;
            mCenterFreqIndex2 = ie.bytes[2] & Constants.BYTE_MASK;
        }
    }

    /**
     * HeOperation: represents the HE Operation IE
     */
    public static class HeOperation {

        private static final int HE_OPERATION_BASIC_LENGTH = 6;
        private static final int VHT_OPERATION_INFO_PRESENT_MASK = 0x40;
        private static final int HE_6GHZ_INFO_PRESENT_MASK = 0x02;
        private static final int HE_6GHZ_CH_WIDTH_MASK = 0x03;
        private static final int CO_HOSTED_BSS_PRESENT_MASK = 0x80;
        private static final int VHT_OPERATION_INFO_START_INDEX = 6;
        private static final int HE_BW_80_80_160 = 3;

        private boolean mPresent = false;
        private boolean mVhtInfoPresent = false;
        private boolean m6GhzInfoPresent = false;
        private int mChannelWidth;
        private int mPrimaryChannel;
        private int mCenterFreqSeg0;
        private int mCenterFreqSeg1;
        private InformationElement mVhtInfo = null;

        /**
         * Returns whether the HE Information Element is present.
         */
        public boolean isPresent() {
            return mPresent;
        }

        /**
         * Returns whether VHT Information field is present.
         */
        public boolean isVhtInfoPresent() {
            return mVhtInfoPresent;
        }

        /**
         * Returns the VHT Information Element if it exists
         * otherwise, return null.
         */
        public InformationElement getVhtInfoElement() {
            return mVhtInfo;
        }

        /**
         * Returns whether the 6GHz information field is present.
         */
        public boolean is6GhzInfoPresent() {
            return m6GhzInfoPresent;
        }

        /**
         * Returns the Channel BW
         * Only applicable to 6GHz band
         */
        public int getChannelWidth() {
            if (!m6GhzInfoPresent) {
                return ScanResult.UNSPECIFIED;
            } else if (mChannelWidth == 0) {
                return ScanResult.CHANNEL_WIDTH_20MHZ;
            } else if (mChannelWidth == 1) {
                return ScanResult.CHANNEL_WIDTH_40MHZ;
            } else if (mChannelWidth == 2) {
                return ScanResult.CHANNEL_WIDTH_80MHZ;
            } else if (Math.abs(mCenterFreqSeg1 - mCenterFreqSeg0) == 8) {
                return ScanResult.CHANNEL_WIDTH_160MHZ;
            } else {
                return ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ;
            }
        }

        /**
         * Returns the primary channel frequency
         * Only applicable for 6GHz channels
         */
        public int getPrimaryFreq() {
            return ScanResult.convertChannelToFrequencyMhz(mPrimaryChannel,
                        WifiScanner.WIFI_BAND_6_GHZ);
        }

        /**
         * Returns the center frequency for the primary channel
         * Only applicable to 6GHz channels
         */
        public int getCenterFreq0() {
            if (m6GhzInfoPresent) {
                if (mCenterFreqSeg0 == 0) {
                    return 0;
                } else {
                    return ScanResult.convertChannelToFrequencyMhz(mCenterFreqSeg0,
                            WifiScanner.WIFI_BAND_6_GHZ);
                }
            } else {
                return 0;
            }
        }

        /**
         * Returns the center frequency for the secondary channel
         * Only applicable to 6GHz channels
         */
        public int getCenterFreq1() {
            if (m6GhzInfoPresent) {
                if (mCenterFreqSeg1 == 0) {
                    return 0;
                } else {
                    return ScanResult.convertChannelToFrequencyMhz(mCenterFreqSeg1,
                            WifiScanner.WIFI_BAND_6_GHZ);
                }
            } else {
                return 0;
            }
        }

        /** Parse HE Operation IE */
        public void from(InformationElement ie) {
            if (ie.id != InformationElement.EID_EXTENSION_PRESENT
                    || ie.idExt != InformationElement.EID_EXT_HE_OPERATION) {
                throw new IllegalArgumentException("Element id is not HE_OPERATION");
            }

            // Make sure the byte array length is at least the fixed size
            if (ie.bytes.length < HE_OPERATION_BASIC_LENGTH) {
                if (DBG) {
                    Log.w(TAG, "Invalid HE_OPERATION len: " + ie.bytes.length);
                }
                // Skipping parsing of the IE
                return;
            }

            mVhtInfoPresent = (ie.bytes[1] & VHT_OPERATION_INFO_PRESENT_MASK) != 0;
            m6GhzInfoPresent = (ie.bytes[2] & HE_6GHZ_INFO_PRESENT_MASK) != 0;
            boolean coHostedBssPresent = (ie.bytes[1] & CO_HOSTED_BSS_PRESENT_MASK) != 0;
            int expectedLen = HE_OPERATION_BASIC_LENGTH + (mVhtInfoPresent ? 3 : 0)
                    + (coHostedBssPresent ? 1 : 0) + (m6GhzInfoPresent ? 5 : 0);

            // Make sure the byte array length is at least fitting the known parameters
            if (ie.bytes.length < expectedLen) {
                if (DBG) {
                    Log.w(TAG, "Invalid HE_OPERATION len: " + ie.bytes.length);
                }
                // Skipping parsing of the IE
                return;
            }

            // Passed all checks, IE is ready for decoding
            mPresent = true;

            if (mVhtInfoPresent) {
                mVhtInfo = new InformationElement();
                mVhtInfo.id = InformationElement.EID_VHT_OPERATION;
                mVhtInfo.bytes = new byte[5];
                System.arraycopy(ie.bytes, VHT_OPERATION_INFO_START_INDEX, mVhtInfo.bytes, 0, 3);
            }

            if (m6GhzInfoPresent) {
                int startIndx = VHT_OPERATION_INFO_START_INDEX + (mVhtInfoPresent ? 3 : 0)
                        + (coHostedBssPresent ? 1 : 0);

                mChannelWidth = ie.bytes[startIndx + 1] & HE_6GHZ_CH_WIDTH_MASK;
                mPrimaryChannel = ie.bytes[startIndx] & Constants.BYTE_MASK;
                mCenterFreqSeg0 = ie.bytes[startIndx + 2] & Constants.BYTE_MASK;
                mCenterFreqSeg1 = ie.bytes[startIndx + 3] & Constants.BYTE_MASK;
            }
        }
    }

    /**
     * HtCapabilities: represents the HT Capabilities IE
     */
    public static class HtCapabilities {
        private int mMaxNumberSpatialStreams  = 1;
        private boolean mPresent = false;
        /** Returns whether HT Capabilities IE is present */
        public boolean isPresent() {
            return mPresent;
        }
        /**
         * Returns max number of spatial streams if HT Capabilities IE is found and parsed,
         * or 1 otherwise
         */
        public int getMaxNumberSpatialStreams() {
            return mMaxNumberSpatialStreams;
        }

        /** Parse HT Capabilities IE */
        public void from(InformationElement ie) {
            if (ie.id != InformationElement.EID_HT_CAPABILITIES) {
                throw new IllegalArgumentException("Element id is not HT_CAPABILITIES: " + ie.id);
            }
            if (ie.bytes.length < 26) {
                if (DBG) {
                    Log.w(TAG, "Invalid HtCapabilities len: " + ie.bytes.length);
                }
                return;
            }
            int stream1 = ie.bytes[3] & Constants.BYTE_MASK;
            int stream2 = ie.bytes[4] & Constants.BYTE_MASK;
            int stream3 = ie.bytes[5] & Constants.BYTE_MASK;
            int stream4 = ie.bytes[6] & Constants.BYTE_MASK;
            if (DBG) {
                Log.d(TAG, "HT Rx MCS set4: " + Integer.toHexString(stream4));
                Log.d(TAG, "HT Rx MCS set3: " + Integer.toHexString(stream3));
                Log.d(TAG, "HT Rx MCS set2: " + Integer.toHexString(stream2));
                Log.d(TAG, "HT Rx MCS set1: " + Integer.toHexString(stream1));
            }
            mMaxNumberSpatialStreams = (stream4 > 0) ? 4 :
                    ((stream3 > 0) ? 3 :
                    ((stream2 > 0) ? 2 : 1));
            mPresent = true;
        }
    }

    /**
     * VhtCapabilities: represents the VHT Capabilities IE
     */
    public static class VhtCapabilities {
        private int mMaxNumberSpatialStreams = 1;
        private boolean mPresent = false;
        /** Returns whether VHT Capabilities IE is present */
        public boolean isPresent() {
            return mPresent;
        }
        /**
         * Returns max number of spatial streams if VHT Capabilities IE is found and parsed,
         * or 1 otherwise
         */
        public int getMaxNumberSpatialStreams() {
            return mMaxNumberSpatialStreams;
        }
        /** Parse VHT Capabilities IE */
        public void from(InformationElement ie) {
            if (ie.id != InformationElement.EID_VHT_CAPABILITIES) {
                throw new IllegalArgumentException("Element id is not VHT_CAPABILITIES: " + ie.id);
            }
            if (ie.bytes.length < 12) {
                if (DBG) {
                    Log.w(TAG, "Invalid VHT_CAPABILITIES len: " + ie.bytes.length);
                }
                return;
            }
            int mcsMap = ((ie.bytes[5] & Constants.BYTE_MASK) << 8)
                    + (ie.bytes[4] & Constants.BYTE_MASK);
            mMaxNumberSpatialStreams = parseMaxNumberSpatialStreamsFromMcsMap(mcsMap);
            mPresent = true;
        }
    }

    /**
     * HeCapabilities: represents the HE Capabilities IE
     */
    public static class HeCapabilities {
        private int mMaxNumberSpatialStreams = 1;
        private boolean mPresent = false;
        /** Returns whether HE Capabilities IE is present */
        public boolean isPresent() {
            return mPresent;
        }
        /**
         * Returns max number of spatial streams if HE Capabilities IE is found and parsed,
         * or 1 otherwise
         */
        public int getMaxNumberSpatialStreams() {
            return mMaxNumberSpatialStreams;
        }
        /** Parse HE Capabilities IE */
        public void from(InformationElement ie) {
            if (ie.id != InformationElement.EID_EXTENSION_PRESENT
                    || ie.idExt != InformationElement.EID_EXT_HE_CAPABILITIES) {
                throw new IllegalArgumentException("Element id is not HE_CAPABILITIES: " + ie.id);
            }
            if (ie.bytes.length < 21) {
                if (DBG) {
                    Log.w(TAG, "Invalid HE_CAPABILITIES len: " + ie.bytes.length);
                }
                return;
            }
            int mcsMap = ((ie.bytes[18] & Constants.BYTE_MASK) << 8)
                    + (ie.bytes[17] & Constants.BYTE_MASK);
            mMaxNumberSpatialStreams = parseMaxNumberSpatialStreamsFromMcsMap(mcsMap);
            mPresent = true;
        }
    }

    private static int parseMaxNumberSpatialStreamsFromMcsMap(int mcsMap) {
        int maxNumberSpatialStreams = 1;
        for (int i = 8; i >= 1; --i) {
            int streamMap = mcsMapToStreamMap(mcsMap, i);
            // 3 means unsupported
            if (streamMap != 3) {
                maxNumberSpatialStreams = i;
                break;
            }
        }
        if (DBG) {
            for (int i = 8; i >= 1; --i) {
                int streamMap = mcsMapToStreamMap(mcsMap, i);
                Log.d(TAG, "Rx MCS set " + i + " : " + streamMap);
            }
        }
        return maxNumberSpatialStreams;
    }

    private static int mcsMapToStreamMap(int mcsMap, int i) {
        return (mcsMap >> ((i - 1) * 2)) & 0x3;
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

            if (ie.bytes.length == 3 || ie.bytes.length == 9) {
                int venueInfo = (int) ByteBufferReader.readInteger(data, ByteOrder.BIG_ENDIAN, 2);
            }

            if (ie.bytes.length == 7 || ie.bytes.length == 9) {
                hessid = ByteBufferReader.readInteger(data, ByteOrder.BIG_ENDIAN, 6);
            }
        }
    }

    public static class RoamingConsortium {
        public int anqpOICount = 0;

        private long[] roamingConsortiums = null;

        public long[] getRoamingConsortiums() {
            return roamingConsortiums;
        }

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
        private static final int ANQP_DOMAIN_ID_PRESENT_BIT = 0x04;
        private static final int ANQP_PPS_MO_ID_BIT = 0x02;
        private static final int OUI_WFA_ALLIANCE = 0x506F9a;
        private static final int OUI_TYPE_HS20 = 0x10;
        private static final int OUI_TYPE_MBO_OCE = 0x16;

        public NetworkDetail.HSRelease hsRelease = null;
        public int anqpDomainID = 0;    // No domain ID treated the same as a 0; unique info per AP.

        public boolean IsMboCapable = false;
        public boolean IsMboApCellularDataAware = false;
        public boolean IsOceCapable = false;
        public int mboAssociationDisallowedReasonCode =
                MboOceConstants.MBO_OCE_ATTRIBUTE_NOT_PRESENT;

        private void parseVsaMboOce(InformationElement ie) {
            ByteBuffer data = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);

            // skip WFA OUI and type parsing as parseVsaMboOce() is called after identifying
            // MBO-OCE OUI type.
            data.getInt();

            while (data.remaining() > 1) {
                int attrId = data.get() & Constants.BYTE_MASK;
                int attrLen = data.get() & Constants.BYTE_MASK;

                if ((attrLen == 0) || (attrLen > data.remaining())) {
                    return;
                }
                byte[] attrBytes = new byte[attrLen];
                data.get(attrBytes);
                switch (attrId) {
                    case MboOceConstants.MBO_OCE_AID_MBO_AP_CAPABILITY_INDICATION:
                        IsMboCapable = true;
                        IsMboApCellularDataAware = (attrBytes[0]
                                & MboOceConstants.MBO_AP_CAP_IND_ATTR_CELL_DATA_AWARE) != 0;
                        break;
                    case MboOceConstants.MBO_OCE_AID_ASSOCIATION_DISALLOWED:
                        mboAssociationDisallowedReasonCode = attrBytes[0] & Constants.BYTE_MASK;
                        break;
                    case MboOceConstants.MBO_OCE_AID_OCE_AP_CAPABILITY_INDICATION:
                        IsOceCapable = true;
                        break;
                    default:
                        break;
                }
            }
            if (DBG) {
                Log.e(TAG, ":parseMboOce MBO: " + IsMboCapable + " cellDataAware: "
                        + IsMboApCellularDataAware + " AssocDisAllowRC: "
                        + mboAssociationDisallowedReasonCode + " :OCE: " + IsOceCapable);
            }
        }

        private void parseVsaHs20(InformationElement ie) {
            ByteBuffer data = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (ie.bytes.length >= 5) {
                // skip WFA OUI and type parsing as parseVsaHs20() is called after identifying
                // HS20 OUI type.
                data.getInt();

                int hsConf = data.get() & Constants.BYTE_MASK;
                switch ((hsConf >> 4) & Constants.NIBBLE_MASK) {
                    case 0:
                        hsRelease = NetworkDetail.HSRelease.R1;
                        break;
                    case 1:
                        hsRelease = NetworkDetail.HSRelease.R2;
                        break;
                    case 2:
                        hsRelease = NetworkDetail.HSRelease.R3;
                        break;
                    default:
                        hsRelease = NetworkDetail.HSRelease.Unknown;
                        break;
                }
                if ((hsConf & ANQP_DOMAIN_ID_PRESENT_BIT) != 0) {
                    // According to Hotspot 2.0 Specification v3.0 section 3.1.1 HS2.0 Indication
                    // element, the size of the element is 5 bytes, and 2 bytes are optionally added
                    // for each optional field; ANQP PPS MO ID and ANQP Domain ID present.
                    int expectedSize = 7;
                    if ((hsConf & ANQP_PPS_MO_ID_BIT) != 0) {
                        expectedSize += 2;
                        if (ie.bytes.length < expectedSize) {
                            throw new IllegalArgumentException(
                                    "HS20 indication element too short: " + ie.bytes.length);
                        }
                        data.getShort(); // Skip 2 bytes
                    }
                    if (ie.bytes.length < expectedSize) {
                        throw new IllegalArgumentException(
                                "HS20 indication element too short: " + ie.bytes.length);
                    }
                    anqpDomainID = data.getShort() & Constants.SHORT_MASK;
                }
            }
        }

        /**
         * Parse the vendor specific information element to build
         * InformationElemmentUtil.vsa object.
         *
         * @param ie -- Information Element
         */
        public void from(InformationElement ie) {
            if (ie.bytes.length < 3) {
                if (DBG) {
                    Log.w(TAG, "Invalid vendor specific element len: " + ie.bytes.length);
                }
                return;
            }

            int oui = (((ie.bytes[0] & Constants.BYTE_MASK) << 16)
                       | ((ie.bytes[1] & Constants.BYTE_MASK) << 8)
                       |  ((ie.bytes[2] & Constants.BYTE_MASK)));

            if (oui == OUI_WFA_ALLIANCE && ie.bytes.length >= 4) {
                int ouiType = ie.bytes[3];
                switch (ouiType) {
                    case OUI_TYPE_HS20:
                        parseVsaHs20(ie);
                        break;
                    case OUI_TYPE_MBO_OCE:
                        parseVsaMboOce(ie);
                        break;
                    default:
                        break;
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
        private static final int WPA_VENDOR_OUI_TYPE_ONE = 0x01f25000;
        private static final int WPS_VENDOR_OUI_TYPE = 0x04f25000;
        private static final short WPA_VENDOR_OUI_VERSION = 0x0001;
        private static final int OWE_VENDOR_OUI_TYPE = 0x1c9a6f50;
        private static final short RSNE_VERSION = 0x0001;

        private static final int WPA_AKM_EAP = 0x01f25000;
        private static final int WPA_AKM_PSK = 0x02f25000;

        private static final int RSN_AKM_EAP = 0x01ac0f00;
        private static final int RSN_AKM_PSK = 0x02ac0f00;
        private static final int RSN_AKM_FT_EAP = 0x03ac0f00;
        private static final int RSN_AKM_FT_PSK = 0x04ac0f00;
        private static final int RSN_AKM_EAP_SHA256 = 0x05ac0f00;
        private static final int RSN_AKM_PSK_SHA256 = 0x06ac0f00;
        private static final int RSN_AKM_SAE = 0x08ac0f00;
        private static final int RSN_AKM_FT_SAE = 0x09ac0f00;
        private static final int RSN_AKM_OWE = 0x12ac0f00;
        private static final int RSN_AKM_EAP_SUITE_B_192 = 0x0cac0f00;
        private static final int RSN_OSEN = 0x019a6f50;
        private static final int RSN_AKM_FILS_SHA256 = 0x0eac0f00;
        private static final int RSN_AKM_FILS_SHA384 = 0x0fac0f00;

        private static final int WPA_CIPHER_NONE = 0x00f25000;
        private static final int WPA_CIPHER_TKIP = 0x02f25000;
        private static final int WPA_CIPHER_CCMP = 0x04f25000;

        private static final int RSN_CIPHER_NONE = 0x00ac0f00;
        private static final int RSN_CIPHER_TKIP = 0x02ac0f00;
        private static final int RSN_CIPHER_CCMP = 0x04ac0f00;
        private static final int RSN_CIPHER_NO_GROUP_ADDRESSED = 0x07ac0f00;
        private static final int RSN_CIPHER_GCMP_256 = 0x09ac0f00;

        public List<Integer> protocol;
        public List<List<Integer>> keyManagement;
        public List<List<Integer>> pairwiseCipher;
        public List<Integer> groupCipher;
        public boolean isESS;
        public boolean isIBSS;
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
                protocol.add(ScanResult.PROTOCOL_RSN);

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
                        case RSN_AKM_EAP:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_EAP);
                            break;
                        case RSN_AKM_PSK:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_PSK);
                            break;
                        case RSN_AKM_FT_EAP:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_FT_EAP);
                            break;
                        case RSN_AKM_FT_PSK:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_FT_PSK);
                            break;
                        case RSN_AKM_EAP_SHA256:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_EAP_SHA256);
                            break;
                        case RSN_AKM_PSK_SHA256:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_PSK_SHA256);
                            break;
                        case RSN_AKM_SAE:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_SAE);
                            break;
                        case RSN_AKM_FT_SAE:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_FT_SAE);
                            break;
                        case RSN_AKM_OWE:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_OWE);
                            break;
                        case RSN_AKM_EAP_SUITE_B_192:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_EAP_SUITE_B_192);
                            break;
                        case RSN_OSEN:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_OSEN);
                            break;
                        case RSN_AKM_FILS_SHA256:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_FILS_SHA256);
                            break;
                        case RSN_AKM_FILS_SHA384:
                            rsnKeyManagement.add(ScanResult.KEY_MGMT_FILS_SHA384);
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

        private static @Cipher int parseWpaCipher(int cipher) {
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

        private static @Cipher int parseRsnCipher(int cipher) {
            switch (cipher) {
                case RSN_CIPHER_NONE:
                    return ScanResult.CIPHER_NONE;
                case RSN_CIPHER_TKIP:
                    return ScanResult.CIPHER_TKIP;
                case RSN_CIPHER_CCMP:
                    return ScanResult.CIPHER_CCMP;
                case RSN_CIPHER_GCMP_256:
                    return ScanResult.CIPHER_GCMP_256;
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
                if (buf.getShort() != WPA_VENDOR_OUI_VERSION) {
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
         * @param ies            -- Information Element array
         * @param beaconCap      -- 16-bit Beacon Capability Information field
         * @param isOweSupported -- Boolean flag to indicate if OWE is supported by the device
         */

        public void from(InformationElement[] ies, int beaconCap, boolean isOweSupported) {
            protocol = new ArrayList<>();
            keyManagement = new ArrayList<>();
            groupCipher = new ArrayList<>();
            pairwiseCipher = new ArrayList<>();

            if (ies == null) {
                return;
            }
            isESS = (beaconCap & NativeScanResult.BSS_CAPABILITY_ESS) != 0;
            isIBSS = (beaconCap & NativeScanResult.BSS_CAPABILITY_IBSS) != 0;
            isPrivacy = (beaconCap & NativeScanResult.BSS_CAPABILITY_PRIVACY) != 0;
            for (InformationElement ie : ies) {
                WifiNl80211Manager.OemSecurityType oemSecurityType =
                        WifiNl80211Manager.parseOemSecurityTypeElement(
                        ie.id, ie.idExt, ie.bytes);
                if (oemSecurityType != null
                        && oemSecurityType.protocol != ScanResult.PROTOCOL_NONE) {
                    protocol.add(oemSecurityType.protocol);
                    keyManagement.add(oemSecurityType.keyManagement);
                    pairwiseCipher.add(oemSecurityType.pairwiseCipher);
                    groupCipher.add(oemSecurityType.groupCipher);
                }

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
                    if (isOweSupported && isOweElement(ie)) {
                        /* From RFC 8110: Once the client and AP have finished 802.11 association,
                           they then complete the Diffie-Hellman key exchange and create a Pairwise
                           Master Key (PMK) and its associated identifier, PMKID [IEEE802.11].
                           Upon completion of 802.11 association, the AP initiates the 4-way
                           handshake to the client using the PMK generated above.  The 4-way
                           handshake generates a Key-Encrypting Key (KEK), a Key-Confirmation
                           Key (KCK), and a Message Integrity Code (MIC) to use for protection
                           of the frames that define the 4-way handshake.

                           We check if OWE is supported here because we are adding the OWE
                           capabilities to the Open BSS. Non-supporting devices need to see this
                           open network and ignore this element. Supporting devices need to hide
                           the Open BSS of OWE in transition mode and connect to the Hidden one.
                        */
                        protocol.add(ScanResult.PROTOCOL_RSN);
                        groupCipher.add(ScanResult.CIPHER_CCMP);
                        ArrayList<Integer> owePairwiseCipher = new ArrayList<>();
                        owePairwiseCipher.add(ScanResult.CIPHER_CCMP);
                        pairwiseCipher.add(owePairwiseCipher);
                        ArrayList<Integer> oweKeyManagement = new ArrayList<>();
                        oweKeyManagement.add(ScanResult.KEY_MGMT_OWE_TRANSITION);
                        keyManagement.add(oweKeyManagement);
                    }
                }
            }
        }

        private static boolean isOweElement(InformationElement ie) {
            ByteBuffer buf = ByteBuffer.wrap(ie.bytes).order(ByteOrder.LITTLE_ENDIAN);
            try {
                // OWE OUI and type
                return (buf.getInt() == OWE_VENDOR_OUI_TYPE);
            } catch (BufferUnderflowException e) {
                Log.e("IE_Capabilities", "Couldn't parse VSA IE, buffer underflow");
                return false;
            }
        }

        private String protocolToString(@Protocol int protocol) {
            switch (protocol) {
                case ScanResult.PROTOCOL_NONE:
                    return "None";
                case ScanResult.PROTOCOL_WPA:
                    return "WPA";
                case ScanResult.PROTOCOL_RSN:
                    return "RSN";
                case ScanResult.PROTOCOL_OSEN:
                    return "OSEN";
                case ScanResult.PROTOCOL_WAPI:
                    return "WAPI";
                default:
                    return "?";
            }
        }

        private String keyManagementToString(@KeyMgmt int akm) {
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
                case ScanResult.KEY_MGMT_OWE:
                    return "OWE";
                case ScanResult.KEY_MGMT_OWE_TRANSITION:
                    return "OWE_TRANSITION";
                case ScanResult.KEY_MGMT_SAE:
                    return "SAE";
                case ScanResult.KEY_MGMT_FT_SAE:
                    return "FT/SAE";
                case ScanResult.KEY_MGMT_EAP_SUITE_B_192:
                    return "EAP_SUITE_B_192";
                case ScanResult.KEY_MGMT_OSEN:
                    return "OSEN";
                case ScanResult.KEY_MGMT_WAPI_PSK:
                    return "WAPI-PSK";
                case ScanResult.KEY_MGMT_WAPI_CERT:
                    return "WAPI-CERT";
                case ScanResult.KEY_MGMT_FILS_SHA256:
                    return "FILS-SHA256";
                case ScanResult.KEY_MGMT_FILS_SHA384:
                    return "FILS-SHA384";
                default:
                    return "?";
            }
        }

        private String cipherToString(@Cipher int cipher) {
            switch (cipher) {
                case ScanResult.CIPHER_NONE:
                    return "None";
                case ScanResult.CIPHER_CCMP:
                    return "CCMP";
                case ScanResult.CIPHER_GCMP_256:
                    return "GCMP-256";
                case ScanResult.CIPHER_TKIP:
                    return "TKIP";
                case ScanResult.CIPHER_SMS4:
                    return "SMS4";
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
            StringBuilder capabilities = new StringBuilder();
            // private Beacon without an RSNE or WPA IE, hence WEP0
            boolean isWEP = (protocol.isEmpty()) && isPrivacy;

            if (isWEP) {
                capabilities.append("[WEP]");
            }
            for (int i = 0; i < protocol.size(); i++) {
                String capability = generateCapabilitiesStringPerProtocol(i);
                // add duplicate capabilities for WPA2 for backward compatibility:
                // duplicate "RSN" entries as "WPA2"
                String capWpa2 = generateWPA2CapabilitiesString(capability, i);
                capabilities.append(capWpa2);
                capabilities.append(capability);
            }
            if (isESS) {
                capabilities.append("[ESS]");
            }
            if (isIBSS) {
                capabilities.append("[IBSS]");
            }
            if (isWPS) {
                capabilities.append("[WPS]");
            }

            return capabilities.toString();
        }

        /**
         * Build the Capability String for one protocol
         * @param index: index number of the protocol
         * @return security string for one protocol
         */
        private String generateCapabilitiesStringPerProtocol(int index) {
            StringBuilder capability = new StringBuilder();
            capability.append("[").append(protocolToString(protocol.get(index)));

            if (index < keyManagement.size()) {
                for (int j = 0; j < keyManagement.get(index).size(); j++) {
                    capability.append((j == 0) ? "-" : "+").append(
                            keyManagementToString(keyManagement.get(index).get(j)));
                }
            }
            if (index < pairwiseCipher.size()) {
                for (int j = 0; j < pairwiseCipher.get(index).size(); j++) {
                    capability.append((j == 0) ? "-" : "+").append(
                            cipherToString(pairwiseCipher.get(index).get(j)));
                }
            }
            capability.append("]");
            return capability.toString();
        }

        /**
         * Build the duplicate Capability String for WPA2
         * @param cap: original capability String
         * @param index: index number of the protocol
         * @return security string for WPA2, empty String if protocol is not WPA2
         */
        private String generateWPA2CapabilitiesString(String cap, int index) {
            StringBuilder capWpa2 = new StringBuilder();
            // if not WPA2, return empty String
            if (cap.contains("EAP_SUITE_B_192")
                    || (!cap.contains("RSN-EAP") && !cap.contains("RSN-FT/EAP")
                    && !cap.contains("RSN-PSK") && !cap.contains("RSN-FT/PSK"))) {
                return "";
            }
            capWpa2.append("[").append("WPA2");
            if (index < keyManagement.size()) {
                for (int j = 0; j < keyManagement.get(index).size(); j++) {
                    capWpa2.append((j == 0) ? "-" : "+").append(
                            keyManagementToString(keyManagement.get(index).get(j)));
                    // WPA3/WPA2 transition mode
                    if (cap.contains("SAE")) {
                        break;
                    }
                }
            }
            if (index < pairwiseCipher.size()) {
                for (int j = 0; j < pairwiseCipher.get(index).size(); j++) {
                    capWpa2.append((j == 0) ? "-" : "+").append(
                            cipherToString(pairwiseCipher.get(index).get(j)));
                }
            }
            capWpa2.append("]");
            return capWpa2.toString();
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
     * This util class determines the 802.11 standard (a/b/g/n/ac/ax) being used
     */
    public static class WifiMode {
        public static final int MODE_UNDEFINED = 0; // Unknown/undefined
        public static final int MODE_11A = 1;       // 802.11a
        public static final int MODE_11B = 2;       // 802.11b
        public static final int MODE_11G = 3;       // 802.11g
        public static final int MODE_11N = 4;       // 802.11n
        public static final int MODE_11AC = 5;      // 802.11ac
        public static final int MODE_11AX = 6;      // 802.11ax
        //<TODO> add support for 802.11ad and be more selective instead of defaulting to 11A

        /**
         * Use frequency, max supported rate, and the existence of HE, VHT, HT & ERP fields in scan
         * scan result to determine the 802.11 Wifi standard being used.
         */
        public static int determineMode(int frequency, int maxRate, boolean foundHe,
                boolean foundVht, boolean foundHt, boolean foundErp) {
            if (foundHe) {
                return MODE_11AX;
            } else if (!ScanResult.is24GHz(frequency) && foundVht) {
                // Do not include subset of VHT on 2.4 GHz vendor extension
                // in consideration for reporting VHT.
                return MODE_11AC;
            } else if (foundHt) {
                return MODE_11N;
            } else if (foundErp) {
                return MODE_11G;
            } else if (ScanResult.is24GHz(frequency)) {
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
                case MODE_11AX:
                    return "MODE_11AX";
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
