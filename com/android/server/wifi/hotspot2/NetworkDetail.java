package com.android.server.wifi.hotspot2;

import android.net.wifi.ScanResult;
import android.util.Log;

import com.android.server.wifi.anqp.ANQPElement;
import com.android.server.wifi.anqp.Constants;
import com.android.server.wifi.anqp.VenueNameElement;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.android.server.wifi.anqp.Constants.BYTES_IN_EUI48;
import static com.android.server.wifi.anqp.Constants.BYTE_MASK;
import static com.android.server.wifi.anqp.Constants.getInteger;

public class NetworkDetail {

    private static final int EID_SSID = 0;
    private static final int EID_BSSLoad = 11;
    private static final int EID_HT_OPERATION = 61;
    private static final int EID_VHT_OPERATION = 192;
    private static final int EID_Interworking = 107;
    private static final int EID_RoamingConsortium = 111;
    private static final int EID_ExtendedCaps = 127;
    private static final int EID_VSA = 221;

    private static final int ANQP_DOMID_BIT = 0x04;
    private static final int RTT_RESP_ENABLE_BIT = 70;

    private static final long SSID_UTF8_BIT = 0x0001000000000000L;
    //turn off when SHIP
    private static final boolean DBG = true;
    private static final boolean VDBG = false;
    
    private static final String TAG = "NetworkDetail:";

    public enum Ant {
        Private,
        PrivateWithGuest,
        ChargeablePublic,
        FreePublic,
        Personal,
        EmergencyOnly,
        Resvd6,
        Resvd7,
        Resvd8,
        Resvd9,
        Resvd10,
        Resvd11,
        Resvd12,
        Resvd13,
        TestOrExperimental,
        Wildcard
    }

    public enum HSRelease {
        R1,
        R2,
        Unknown
    }

    // General identifiers:
    private final String mSSID;
    private final long mHESSID;
    private final long mBSSID;

    // BSS Load element:
    private final int mStationCount;
    private final int mChannelUtilization;
    private final int mCapacity;

    //channel detailed information
   /*
    * 0 -- 20 MHz
    * 1 -- 40 MHz
    * 2 -- 80 MHz
    * 3 -- 160 MHz
    * 4 -- 80 + 80 MHz
    */
    private final int mChannelWidth;
    private final int mPrimaryFreq;
    private final int mCenterfreq0;
    private final int mCenterfreq1;
    private final boolean m80211McRTTResponder;
    /*
     * From Interworking element:
     * mAnt non null indicates the presence of Interworking, i.e. 802.11u
     * mVenueGroup and mVenueType may be null if not present in the Interworking element.
     */
    private final Ant mAnt;
    private final boolean mInternet;
    private final VenueNameElement.VenueGroup mVenueGroup;
    private final VenueNameElement.VenueType mVenueType;

    /*
     * From HS20 Indication element:
     * mHSRelease is null only if the HS20 Indication element was not present.
     * mAnqpDomainID is set to -1 if not present in the element.
     */
    private final HSRelease mHSRelease;
    private final int mAnqpDomainID;

    /*
     * From beacon:
     * mAnqpOICount is how many additional OIs are available through ANQP.
     * mRoamingConsortiums is either null, if the element was not present, or is an array of
     * 1, 2 or 3 longs in which the roaming consortium values occupy the LSBs.
     */
    private final int mAnqpOICount;
    private final long[] mRoamingConsortiums;

    private final Long mExtendedCapabilities;

    private final Map<Constants.ANQPElementType, ANQPElement> mANQPElements;

    public NetworkDetail(String bssid, String infoElements, List<String> anqpLines, int freq) {

        if (infoElements == null) {
            throw new IllegalArgumentException("Null information element string");
        }
        int separator = infoElements.indexOf('=');
        if (separator<0) {
            throw new IllegalArgumentException("No element separator");
        }

        mBSSID = Utils.parseMac(bssid);

        ByteBuffer data = ByteBuffer.wrap(Utils.hexToBytes(infoElements.substring(separator + 1)))
                .order(ByteOrder.LITTLE_ENDIAN);

        String ssid = null;
        byte[] ssidOctets = null;
        int stationCount = 0;
        int channelUtilization = 0;
        int capacity = 0;

        Ant ant = null;
        boolean internet = false;
        VenueNameElement.VenueGroup venueGroup = null;
        VenueNameElement.VenueType venueType = null;
        long hessid = 0L;

        int anqpOICount = 0;
        long[] roamingConsortiums = null;

        HSRelease hsRelease = null;
        int anqpDomainID = 0;       // No domain ID treated the same as a 0; unique info per AP.

        Long extendedCapabilities = null;

        int secondChanelOffset = 0;
        int channelMode = 0;
        int centerFreqIndex1 = 0;
        int centerFreqIndex2 = 0;
        boolean RTTResponder = false;

        RuntimeException exception = null;

        try {
            while (data.remaining() > 1) {
                int eid = data.get() & Constants.BYTE_MASK;
                int elementLength = data.get() & Constants.BYTE_MASK;

                if (elementLength > data.remaining()) {
                    throw new IllegalArgumentException("Element length " + elementLength +
                            " exceeds payload length " + data.remaining() +
                            " @ " + data.position());
                }
                if (eid == 0 && elementLength == 0 && ssidOctets != null) {
                    // Don't overwrite SSID (eid 0) with trailing zero garbage
                    continue;
                }

                ByteBuffer element;

                switch (eid) {
                    case EID_SSID:
                        ssidOctets = new byte[elementLength];
                        data.get(ssidOctets);
                        break;
                    case EID_BSSLoad:
                        if (elementLength != 5) {
                            throw new IllegalArgumentException("BSS Load element length is not 5: " +
                                    elementLength);
                        }
                        stationCount = data.getShort() & Constants.SHORT_MASK;
                        channelUtilization = data.get() & Constants.BYTE_MASK;
                        capacity = data.getShort() & Constants.SHORT_MASK;
                        break;
                    case EID_HT_OPERATION:
                        element = getAndAdvancePayload(data, elementLength);
                        int primary_channel = element.get();
                        secondChanelOffset = element.get() & 0x3;
                        break;
                    case EID_VHT_OPERATION:
                        element = getAndAdvancePayload(data, elementLength);
                        channelMode = element.get() & Constants.BYTE_MASK;
                        centerFreqIndex1 = element.get() & Constants.BYTE_MASK;
                        centerFreqIndex2 = element.get() & Constants.BYTE_MASK;
                        break;
                    case EID_Interworking:
                        int anOptions = data.get() & Constants.BYTE_MASK;
                        ant = Ant.values()[anOptions & 0x0f];
                        internet = (anOptions & 0x10) != 0;
                        // Len 1 none, 3 venue-info, 7 HESSID, 9 venue-info & HESSID
                        if (elementLength == 3 || elementLength == 9) {
                            try {
                                ByteBuffer vinfo = data.duplicate();
                                vinfo.limit(vinfo.position() + 2);
                                VenueNameElement vne =
                                        new VenueNameElement(Constants.ANQPElementType.ANQPVenueName,
                                                vinfo);
                                venueGroup = vne.getGroup();
                                venueType = vne.getType();
                                data.getShort();
                            } catch (ProtocolException pe) {
                                /*Cannot happen*/
                            }
                        } else if (elementLength != 1 && elementLength != 7) {
                            throw new IllegalArgumentException("Bad Interworking element length: " +
                                    elementLength);
                        }
                        if (elementLength == 7 || elementLength == 9) {
                            hessid = getInteger(data, ByteOrder.BIG_ENDIAN, 6);
                        }
                        break;
                    case EID_RoamingConsortium:
                        anqpOICount = data.get() & Constants.BYTE_MASK;

                        int oi12Length = data.get() & Constants.BYTE_MASK;
                        int oi1Length = oi12Length & Constants.NIBBLE_MASK;
                        int oi2Length = (oi12Length >>> 4) & Constants.NIBBLE_MASK;
                        int oi3Length = elementLength - 2 - oi1Length - oi2Length;
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
                                    getInteger(data, ByteOrder.BIG_ENDIAN, oi1Length);
                        }
                        if (oi2Length > 0 && roamingConsortiums.length > 1) {
                            roamingConsortiums[1] =
                                    getInteger(data, ByteOrder.BIG_ENDIAN, oi2Length);
                        }
                        if (oi3Length > 0 && roamingConsortiums.length > 2) {
                            roamingConsortiums[2] =
                                    getInteger(data, ByteOrder.BIG_ENDIAN, oi3Length);
                        }
                        break;
                    case EID_VSA:
                        element = getAndAdvancePayload(data, elementLength);
                        if (elementLength >= 5 && element.getInt() == Constants.HS20_FRAME_PREFIX) {
                            int hsConf = element.get() & Constants.BYTE_MASK;
                            switch ((hsConf >> 4) & Constants.NIBBLE_MASK) {
                                case 0:
                                    hsRelease = HSRelease.R1;
                                    break;
                                case 1:
                                    hsRelease = HSRelease.R2;
                                    break;
                                default:
                                    hsRelease = HSRelease.Unknown;
                                    break;
                            }
                            if ((hsConf & ANQP_DOMID_BIT) != 0) {
                                if (elementLength < 7) {
                                    throw new IllegalArgumentException(
                                            "HS20 indication element too short: " + elementLength);
                                }
                                anqpDomainID = element.getShort() & Constants.SHORT_MASK;
                            }
                        }
                        break;
                    case EID_ExtendedCaps:
                        element = data.duplicate();
                        extendedCapabilities =
                                Constants.getInteger(data, ByteOrder.LITTLE_ENDIAN, elementLength);

                        int index = RTT_RESP_ENABLE_BIT / 8;
                        byte offset = RTT_RESP_ENABLE_BIT % 8;

                        if (elementLength < index + 1) {
                            RTTResponder = false;
                            element.position(element.position() + elementLength);
                            break;
                        }

                        element.position(element.position() + index);

                        RTTResponder = (element.get() & (0x1 << offset)) != 0;
                        break;
                    default:
                        data.position(data.position() + elementLength);
                        break;
                }
            }
        }
        catch (IllegalArgumentException | BufferUnderflowException | ArrayIndexOutOfBoundsException e) {
            Log.d(Utils.hs2LogTag(getClass()), "Caught " + e);
            if (ssidOctets == null) {
                throw new IllegalArgumentException("Malformed IE string (no SSID)", e);
            }
            exception = e;
        }

        if (ssidOctets != null) {
            boolean strictUTF8 = extendedCapabilities != null &&
                    ( extendedCapabilities & SSID_UTF8_BIT ) != 0;

            /*
             * Strict use of the "UTF-8 SSID" bit by APs appears to be spotty at best even if the
             * encoding truly is in UTF-8. An unconditional attempt to decode the SSID as UTF-8 is
             * therefore always made with a fall back to 8859-1 under normal circumstances.
             * If, however, a previous exception was detected and the UTF-8 bit is set, failure to
             * decode the SSID will be used as an indication that the whole frame is malformed and
             * an exception will be triggered.
             */
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            try {
                CharBuffer decoded = decoder.decode(ByteBuffer.wrap(ssidOctets));
                ssid = decoded.toString();
            }
            catch (CharacterCodingException cce) {
                ssid = null;
            }

            if (ssid == null) {
                if (strictUTF8 && exception != null) {
                    throw new IllegalArgumentException("Failed to decode SSID in dubious IE string");
                }
                else {
                    ssid = new String(ssidOctets, StandardCharsets.ISO_8859_1);
                }
            }
        }

        mSSID = ssid;
        mHESSID = hessid;
        mStationCount = stationCount;
        mChannelUtilization = channelUtilization;
        mCapacity = capacity;
        mAnt = ant;
        mInternet = internet;
        mVenueGroup = venueGroup;
        mVenueType = venueType;
        mHSRelease = hsRelease;
        mAnqpDomainID = anqpDomainID;
        mAnqpOICount = anqpOICount;
        mRoamingConsortiums = roamingConsortiums;
        mExtendedCapabilities = extendedCapabilities;
        mANQPElements = SupplicantBridge.parseANQPLines(anqpLines);
        //set up channel info
        mPrimaryFreq = freq;

        if (channelMode != 0) {
            // 80 or 160 MHz
            mChannelWidth = channelMode + 1;
            mCenterfreq0 = (centerFreqIndex1 - 36) * 5 + 5180;
            if(channelMode > 1) { //160MHz
                mCenterfreq1 = (centerFreqIndex2 - 36) * 5 + 5180;
            } else {
                mCenterfreq1 = 0;
            }
        } else {
            //20 or 40 MHz
            if (secondChanelOffset != 0) {//40MHz
                mChannelWidth = 1;
                if (secondChanelOffset == 1) {
                    mCenterfreq0 = mPrimaryFreq + 20;
                } else if (secondChanelOffset == 3) {
                    mCenterfreq0 = mPrimaryFreq - 20;
                } else {
                    mCenterfreq0 = 0;
                    Log.e(TAG,"Error on secondChanelOffset");
                }
            } else {
                mCenterfreq0 = 0;
                mChannelWidth = 0;
            }
            mCenterfreq1 = 0;
        }
        m80211McRTTResponder = RTTResponder;
        if (VDBG) {
            Log.d(TAG, mSSID + "ChannelWidth is: " + mChannelWidth + " PrimaryFreq: " + mPrimaryFreq +
                    " mCenterfreq0: " + mCenterfreq0 + " mCenterfreq1: " + mCenterfreq1 +
                    (m80211McRTTResponder ? "Support RTT reponder" : "Do not support RTT responder"));
        }
    }

    private static ByteBuffer getAndAdvancePayload(ByteBuffer data, int plLength) {
        ByteBuffer payload = data.duplicate().order(data.order());
        payload.limit(payload.position() + plLength);
        data.position(data.position() + plLength);
        return payload;
    }

    private NetworkDetail(NetworkDetail base, Map<Constants.ANQPElementType, ANQPElement> anqpElements) {
        mSSID = base.mSSID;
        mBSSID = base.mBSSID;
        mHESSID = base.mHESSID;
        mStationCount = base.mStationCount;
        mChannelUtilization = base.mChannelUtilization;
        mCapacity = base.mCapacity;
        mAnt = base.mAnt;
        mInternet = base.mInternet;
        mVenueGroup = base.mVenueGroup;
        mVenueType = base.mVenueType;
        mHSRelease = base.mHSRelease;
        mAnqpDomainID = base.mAnqpDomainID;
        mAnqpOICount = base.mAnqpOICount;
        mRoamingConsortiums = base.mRoamingConsortiums;
        mExtendedCapabilities = base.mExtendedCapabilities;
        mANQPElements = anqpElements;
        mChannelWidth = base.mChannelWidth;
        mPrimaryFreq = base.mPrimaryFreq;
        mCenterfreq0 = base.mCenterfreq0;
        mCenterfreq1 = base.mCenterfreq1;
        m80211McRTTResponder = base.m80211McRTTResponder;
    }

    public NetworkDetail complete(Map<Constants.ANQPElementType, ANQPElement> anqpElements) {
        return new NetworkDetail(this, anqpElements);
    }

    private static long parseMac(String s) {

        long mac = 0;
        int count = 0;
        for (int n = 0; n < s.length(); n++) {
            int nibble = Utils.fromHex(s.charAt(n), true);
            if (nibble >= 0) {
                mac = (mac << 4) | nibble;
                count++;
            }
        }
        if (count < 12 || (count&1) == 1) {
            throw new IllegalArgumentException("Bad MAC address: '" + s + "'");
        }
        return mac;
    }

    public boolean has80211uInfo() {
        return mAnt != null || mRoamingConsortiums != null || mHSRelease != null;
    }

    public boolean hasInterworking() {
        return mAnt != null;
    }

    public String getSSID() {
        return mSSID;
    }

    public String getTrimmedSSID() {
        for (int n = 0; n < mSSID.length(); n++) {
            if (mSSID.charAt(n) != 0) {
                return mSSID;
            }
        }
        return "";
    }

    public long getHESSID() {
        return mHESSID;
    }

    public long getBSSID() {
        return mBSSID;
    }

    public int getStationCount() {
        return mStationCount;
    }

    public int getChannelUtilization() {
        return mChannelUtilization;
    }

    public int getCapacity() {
        return mCapacity;
    }

    public boolean isInterworking() {
        return mAnt != null;
    }

    public Ant getAnt() {
        return mAnt;
    }

    public boolean isInternet() {
        return mInternet;
    }

    public VenueNameElement.VenueGroup getVenueGroup() {
        return mVenueGroup;
    }

    public VenueNameElement.VenueType getVenueType() {
        return mVenueType;
    }

    public HSRelease getHSRelease() {
        return mHSRelease;
    }

    public int getAnqpDomainID() {
        return mAnqpDomainID;
    }

    public int getAnqpOICount() {
        return mAnqpOICount;
    }

    public long[] getRoamingConsortiums() {
        return mRoamingConsortiums;
    }

    public Long getExtendedCapabilities() {
        return mExtendedCapabilities;
    }

    public Map<Constants.ANQPElementType, ANQPElement> getANQPElements() {
        return mANQPElements;
    }

    public int getChannelWidth() {
        return mChannelWidth;
    }

    public int getCenterfreq0() {
        return mCenterfreq0;
    }

    public int getCenterfreq1() {
        return mCenterfreq1;
    }

    public boolean is80211McResponderSupport() {
        return m80211McRTTResponder;
    }

    public boolean isSSID_UTF8() {
        return mExtendedCapabilities != null && (mExtendedCapabilities & SSID_UTF8_BIT) != 0;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (thatObject == null || getClass() != thatObject.getClass()) {
            return false;
        }

        NetworkDetail that = (NetworkDetail)thatObject;

        return getSSID().equals(that.getSSID()) && getBSSID() == that.getBSSID();
    }

    @Override
    public int hashCode() {
        return ((mSSID.hashCode() * 31) + (int)(mBSSID >>> 32)) * 31 + (int)mBSSID;
    }

    @Override
    public String toString() {
        return String.format("NetworkInfo{mSSID='%s', mHESSID=%x, mBSSID=%x, mStationCount=%d, " +
                "mChannelUtilization=%d, mCapacity=%d, mAnt=%s, mInternet=%s, " +
                "mVenueGroup=%s, mVenueType=%s, mHSRelease=%s, mAnqpDomainID=%d, " +
                "mAnqpOICount=%d, mRoamingConsortiums=%s}",
                mSSID, mHESSID, mBSSID, mStationCount,
                mChannelUtilization, mCapacity, mAnt, mInternet,
                mVenueGroup, mVenueType, mHSRelease, mAnqpDomainID,
                mAnqpOICount, Utils.roamingConsortiumsToString(mRoamingConsortiums));
    }

    public String toKeyString() {
        return mHESSID != 0 ?
            String.format("'%s':%012x (%012x)", mSSID, mBSSID, mHESSID) :
            String.format("'%s':%012x", mSSID, mBSSID);
    }

    public String getBSSIDString() {
        return toMACString(mBSSID);
    }

    public static String toMACString(long mac) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int n = BYTES_IN_EUI48 - 1; n >= 0; n--) {
            if (first) {
                first = false;
            } else {
                sb.append(':');
            }
            sb.append(String.format("%02x", (mac >>> (n * Byte.SIZE)) & BYTE_MASK));
        }
        return sb.toString();
    }

    private static final String IE = "ie=" +
            "000477696e67" +                // SSID wing
            "0b052a00cf611e" +              // BSS Load 42:207:7777
            "6b091e0a01610408621205" +      // internet:Experimental:Vehicular:Auto:hessid
            "6f0a0e530111112222222229" +    // 14:111111:2222222229
            "dd07506f9a10143a01";           // r2:314

    private static final String IE2 = "ie=000f4578616d706c65204e6574776f726b010882848b960c1218240301012a010432043048606c30140100000fac040100000fac040100000fac0100007f04000000806b091e07010203040506076c027f006f1001531122331020304050010203040506dd05506f9a1000";

    public static void main(String[] args) {
        ScanResult scanResult = new ScanResult();
        scanResult.SSID = "wing";
        scanResult.BSSID = "610408";
        NetworkDetail nwkDetail = new NetworkDetail(scanResult.BSSID, IE2, null, 0);
        System.out.println(nwkDetail);
    }
}
