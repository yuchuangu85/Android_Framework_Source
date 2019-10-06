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
package com.android.server.wifi.util;

import android.util.Log;

import com.android.server.wifi.WifiLoggerHal;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/**
 * This class parses the raw bytes of a network frame, and stores the parsed information in its
 * public fields.
 */
public class FrameParser {
    /**
     * Note: When adding constants derived from network protocol specifications, please encode
     * these constants the same way as the relevant specification, for ease of comparison.
     */

    private static final String TAG = "FrameParser";

    /* These fields hold the information parsed from this frame. */
    public String mMostSpecificProtocolString = "N/A";
    public String mTypeString = "N/A";
    public String mResultString = "N/A";

    /**
     * Parses the contents of a given network frame.
     *
     * @param frameType The type of the frame, as defined in
     * {@link com.android.server.wifi.WifiLoggerHal}.
     * @param frameBytes The raw bytes of the frame to be parsed.
     */
    public FrameParser(byte frameType, byte[] frameBytes) {
        try {
            ByteBuffer frameBuffer = ByteBuffer.wrap(frameBytes);
            frameBuffer.order(ByteOrder.BIG_ENDIAN);
            if (frameType == WifiLoggerHal.FRAME_TYPE_ETHERNET_II) {
                parseEthernetFrame(frameBuffer);
            } else if (frameType == WifiLoggerHal.FRAME_TYPE_80211_MGMT) {
                parseManagementFrame(frameBuffer);
            }
        } catch (BufferUnderflowException | IllegalArgumentException e) {
            Log.e(TAG, "Dissection aborted mid-frame: " + e);
        }
    }

    /**
     * Read one byte into a form that can easily be compared against, or output as, an integer
     * in the range (0, 255).
     */
    private static short getUnsignedByte(ByteBuffer data) {
        return (short) (data.get() & 0x00ff);
    }
    /**
     * Read two bytes into a form that can easily be compared against, or output as, an integer
     * in the range (0, 65535).
     */
    private static int getUnsignedShort(ByteBuffer data) {
        return (data.getShort() & 0xffff);
    }

    private static final int ETHERNET_SRC_MAC_ADDR_LEN = 6;
    private static final int ETHERNET_DST_MAC_ADDR_LEN = 6;
    private static final short ETHERTYPE_IP_V4 = (short) 0x0800;
    private static final short ETHERTYPE_ARP = (short) 0x0806;
    private static final short ETHERTYPE_IP_V6 = (short) 0x86dd;
    private static final short ETHERTYPE_EAPOL = (short) 0x888e;

    private void parseEthernetFrame(ByteBuffer data) {
        mMostSpecificProtocolString = "Ethernet";
        data.position(data.position() + ETHERNET_SRC_MAC_ADDR_LEN + ETHERNET_DST_MAC_ADDR_LEN);
        short etherType = data.getShort();
        switch (etherType) {
            case ETHERTYPE_IP_V4:
                parseIpv4Packet(data);
                return;
            case ETHERTYPE_ARP:
                parseArpPacket(data);
                return;
            case ETHERTYPE_IP_V6:
                parseIpv6Packet(data);
                return;
            case ETHERTYPE_EAPOL:
                parseEapolPacket(data);
                return;
            default:
                return;
        }
    }

    private static final byte IP_V4_VERSION_BYTE_MASK = (byte) 0b11110000;
    private static final byte IP_V4_IHL_BYTE_MASK = (byte) 0b00001111;
    private static final byte IP_V4_ADDR_LEN = 4;
    private static final byte IP_V4_DSCP_AND_ECN_LEN = 1;
    private static final byte IP_V4_TOTAL_LEN_LEN = 2;
    private static final byte IP_V4_ID_LEN = 2;
    private static final byte IP_V4_FLAGS_AND_FRAG_OFFSET_LEN = 2;
    private static final byte IP_V4_TTL_LEN = 1;
    private static final byte IP_V4_HEADER_CHECKSUM_LEN = 2;
    private static final byte IP_V4_SRC_ADDR_LEN = 4;
    private static final byte IP_V4_DST_ADDR_LEN = 4;
    private static final byte IP_PROTO_ICMP = 1;
    private static final byte IP_PROTO_TCP = 6;
    private static final byte IP_PROTO_UDP = 17;
    private static final byte BYTES_PER_QUAD = 4;

    private void parseIpv4Packet(ByteBuffer data) {
        mMostSpecificProtocolString = "IPv4";
        data.mark();
        byte versionAndHeaderLen = data.get();
        int version = (versionAndHeaderLen & IP_V4_VERSION_BYTE_MASK) >> 4;
        if (version != 4) {
            Log.e(TAG, "IPv4 header: Unrecognized protocol version " + version);
            return;
        }

        data.position(data.position() + IP_V4_DSCP_AND_ECN_LEN + IP_V4_TOTAL_LEN_LEN
                + IP_V4_ID_LEN + IP_V4_FLAGS_AND_FRAG_OFFSET_LEN + IP_V4_TTL_LEN);
        short protocolNumber = getUnsignedByte(data);
        data.position(data.position() + IP_V4_HEADER_CHECKSUM_LEN + IP_V4_SRC_ADDR_LEN
                + IP_V4_DST_ADDR_LEN);

        int headerLen = (versionAndHeaderLen & IP_V4_IHL_BYTE_MASK) * BYTES_PER_QUAD;
        data.reset();  // back to start of IPv4 header
        data.position(data.position() + headerLen);

        switch (protocolNumber) {
            case IP_PROTO_ICMP:
                parseIcmpPacket(data);
                break;
            case IP_PROTO_TCP:
                parseTcpPacket(data);
                break;
            case IP_PROTO_UDP:
                parseUdpPacket(data);
                break;
            default:
                break;
        }
    }

    private static final byte TCP_SRC_PORT_LEN = 2;
    private static final int HTTPS_PORT = 443;
    private static final Set<Integer> HTTP_PORTS = new HashSet<>();
    static {
        HTTP_PORTS.add(80);
        HTTP_PORTS.add(3128);
        HTTP_PORTS.add(3132);
        HTTP_PORTS.add(5985);
        HTTP_PORTS.add(8080);
        HTTP_PORTS.add(8088);
        HTTP_PORTS.add(11371);
        HTTP_PORTS.add(1900);
        HTTP_PORTS.add(2869);
        HTTP_PORTS.add(2710);
    }

    private void parseTcpPacket(ByteBuffer data) {
        mMostSpecificProtocolString = "TCP";
        data.position(data.position() + TCP_SRC_PORT_LEN);
        int dstPort = getUnsignedShort(data);

        if (dstPort == HTTPS_PORT) {
            mTypeString = "HTTPS";
        } else if (HTTP_PORTS.contains(dstPort)) {
            mTypeString = "HTTP";
        }
    }

    private static final byte UDP_PORT_BOOTPS = 67;
    private static final byte UDP_PORT_BOOTPC = 68;
    private static final byte UDP_PORT_NTP = 123;
    private static final byte UDP_CHECKSUM_LEN = 2;

    private void parseUdpPacket(ByteBuffer data) {
        mMostSpecificProtocolString = "UDP";
        int srcPort = getUnsignedShort(data);
        int dstPort = getUnsignedShort(data);
        int length = getUnsignedShort(data);

        data.position(data.position() + UDP_CHECKSUM_LEN);
        if ((srcPort == UDP_PORT_BOOTPC && dstPort == UDP_PORT_BOOTPS)
                || (srcPort == UDP_PORT_BOOTPS && dstPort == UDP_PORT_BOOTPC)) {
            parseDhcpPacket(data);
            return;
        }
        if (srcPort == UDP_PORT_NTP || dstPort == UDP_PORT_NTP) {
            mMostSpecificProtocolString = "NTP";
            return;
        }
    }

    private static final byte BOOTP_OPCODE_LEN = 1;
    private static final byte BOOTP_HWTYPE_LEN = 1;
    private static final byte BOOTP_HWADDR_LEN_LEN = 1;
    private static final byte BOOTP_HOPCOUNT_LEN = 1;
    private static final byte BOOTP_TRANSACTION_ID_LEN = 4;
    private static final byte BOOTP_ELAPSED_SECONDS_LEN = 2;
    private static final byte BOOTP_FLAGS_LEN = 2;
    private static final byte BOOTP_CLIENT_HWADDR_LEN = 16;
    private static final byte BOOTP_SERVER_HOSTNAME_LEN = 64;
    private static final short BOOTP_BOOT_FILENAME_LEN = 128;
    private static final byte BOOTP_MAGIC_COOKIE_LEN = 4;
    private static final short DHCP_OPTION_TAG_PAD = 0;
    private static final short DHCP_OPTION_TAG_MESSAGE_TYPE = 53;
    private static final short DHCP_OPTION_TAG_END = 255;

    private void parseDhcpPacket(ByteBuffer data) {
        mMostSpecificProtocolString = "DHCP";
        data.position(data.position() + BOOTP_OPCODE_LEN + BOOTP_HWTYPE_LEN + BOOTP_HWADDR_LEN_LEN
                + BOOTP_HOPCOUNT_LEN + BOOTP_TRANSACTION_ID_LEN + BOOTP_ELAPSED_SECONDS_LEN
                + BOOTP_FLAGS_LEN + IP_V4_ADDR_LEN * 4 + BOOTP_CLIENT_HWADDR_LEN
                + BOOTP_SERVER_HOSTNAME_LEN + BOOTP_BOOT_FILENAME_LEN + BOOTP_MAGIC_COOKIE_LEN);
        while (data.remaining() > 0) {
            short dhcpOptionTag = getUnsignedByte(data);
            if (dhcpOptionTag == DHCP_OPTION_TAG_PAD) {
                continue;
            }
            if (dhcpOptionTag == DHCP_OPTION_TAG_END) {
                break;
            }
            short dhcpOptionLen = getUnsignedByte(data);
            switch (dhcpOptionTag) {
                case DHCP_OPTION_TAG_MESSAGE_TYPE:
                    if (dhcpOptionLen != 1) {
                        Log.e(TAG, "DHCP option len: " + dhcpOptionLen  + " (expected |1|)");
                        return;
                    }
                    mTypeString = decodeDhcpMessageType(getUnsignedByte(data));
                    return;
                default:
                    data.position(data.position() + dhcpOptionLen);
            }
        }
    }

    private static final byte DHCP_MESSAGE_TYPE_DISCOVER = 1;
    private static final byte DHCP_MESSAGE_TYPE_OFFER = 2;
    private static final byte DHCP_MESSAGE_TYPE_REQUEST = 3;
    private static final byte DHCP_MESSAGE_TYPE_DECLINE = 4;
    private static final byte DHCP_MESSAGE_TYPE_ACK = 5;
    private static final byte DHCP_MESSAGE_TYPE_NAK = 6;
    private static final byte DHCP_MESSAGE_TYPE_RELEASE = 7;
    private static final byte DHCP_MESSAGE_TYPE_INFORM = 8;

    private static String decodeDhcpMessageType(short messageType) {
        switch (messageType) {
            case DHCP_MESSAGE_TYPE_DISCOVER:
                return "Discover";
            case DHCP_MESSAGE_TYPE_OFFER:
                return "Offer";
            case DHCP_MESSAGE_TYPE_REQUEST:
                return "Request";
            case DHCP_MESSAGE_TYPE_DECLINE:
                return "Decline";
            case DHCP_MESSAGE_TYPE_ACK:
                return "Ack";
            case DHCP_MESSAGE_TYPE_NAK:
                return "Nak";
            case DHCP_MESSAGE_TYPE_RELEASE:
                return "Release";
            case DHCP_MESSAGE_TYPE_INFORM:
                return "Inform";
            default:
                return "Unknown type " + messageType;
        }
    }

    private static final byte ICMP_TYPE_ECHO_REPLY = 0;
    private static final byte ICMP_TYPE_DEST_UNREACHABLE = 3;
    private static final byte ICMP_TYPE_REDIRECT = 5;
    private static final byte ICMP_TYPE_ECHO_REQUEST = 8;

    private void parseIcmpPacket(ByteBuffer data) {
        mMostSpecificProtocolString = "ICMP";
        short messageType = getUnsignedByte(data);
        switch (messageType) {
            case ICMP_TYPE_ECHO_REPLY:
                mTypeString = "Echo Reply";
                return;
            case ICMP_TYPE_DEST_UNREACHABLE:
                mTypeString = "Destination Unreachable";
                return;
            case ICMP_TYPE_REDIRECT:
                mTypeString = "Redirect";
                return;
            case ICMP_TYPE_ECHO_REQUEST:
                mTypeString = "Echo Request";
                return;
            default:
                mTypeString = "Type " + messageType;
                return;
        }
    }

    private static final byte ARP_HWTYPE_LEN = 2;
    private static final byte ARP_PROTOTYPE_LEN = 2;
    private static final byte ARP_HWADDR_LEN_LEN = 1;
    private static final byte ARP_PROTOADDR_LEN_LEN = 1;
    private static final byte ARP_OPCODE_REQUEST = 1;
    private static final byte ARP_OPCODE_REPLY = 2;

    private void parseArpPacket(ByteBuffer data) {
        mMostSpecificProtocolString = "ARP";
        data.position(data.position() + ARP_HWTYPE_LEN + ARP_PROTOTYPE_LEN + ARP_HWADDR_LEN_LEN
                + ARP_PROTOADDR_LEN_LEN);
        int opCode = getUnsignedShort(data);
        switch (opCode) {
            case ARP_OPCODE_REQUEST:
                mTypeString = "Request";
                break;
            case ARP_OPCODE_REPLY:
                mTypeString = "Reply";
                break;
            default:
                mTypeString = "Operation " + opCode;
        }
    }

    private static final byte IP_V6_PAYLOAD_LENGTH_LEN = 2;
    private static final byte IP_V6_HOP_LIMIT_LEN = 1;
    private static final byte IP_V6_ADDR_LEN = 16;
    private static final byte IP_V6_HEADER_TYPE_HOP_BY_HOP_OPTION = 0;
    private static final byte IP_V6_HEADER_TYPE_ICMP_V6 = 58;
    private static final byte BYTES_PER_OCT = 8;

    private void parseIpv6Packet(ByteBuffer data) {
        mMostSpecificProtocolString = "IPv6";
        int versionClassAndLabel = data.getInt();
        int version = (versionClassAndLabel & 0xf0000000) >> 28;
        if (version != 6) {
            Log.e(TAG, "IPv6 header: invalid IP version " + version);
            return;
        }
        data.position(data.position() + IP_V6_PAYLOAD_LENGTH_LEN);

        short nextHeaderType = getUnsignedByte(data);
        data.position(data.position() + IP_V6_HOP_LIMIT_LEN + IP_V6_ADDR_LEN * 2);
        while (nextHeaderType == IP_V6_HEADER_TYPE_HOP_BY_HOP_OPTION) {
            int thisHeaderLen;
            data.mark();
            nextHeaderType = getUnsignedByte(data);
            thisHeaderLen = (getUnsignedByte(data) + 1) * BYTES_PER_OCT;
            data.reset();  // back to start of this header
            data.position(data.position() + thisHeaderLen);
        }
        switch (nextHeaderType) {
            case IP_V6_HEADER_TYPE_ICMP_V6:
                parseIcmpV6Packet(data);
                return;
            default:
                mTypeString = "Option/Protocol " + nextHeaderType;
                return;
        }
    }

    private static final short ICMP_V6_TYPE_ECHO_REQUEST = 128;
    private static final short ICMP_V6_TYPE_ECHO_REPLY = 129;
    private static final short ICMP_V6_TYPE_ROUTER_SOLICITATION = 133;
    private static final short ICMP_V6_TYPE_ROUTER_ADVERTISEMENT = 134;
    private static final short ICMP_V6_TYPE_NEIGHBOR_SOLICITATION = 135;
    private static final short ICMP_V6_TYPE_NEIGHBOR_ADVERTISEMENT = 136;
    private static final short ICMP_V6_TYPE_MULTICAST_LISTENER_DISCOVERY = 143;

    private void parseIcmpV6Packet(ByteBuffer data) {
        mMostSpecificProtocolString = "ICMPv6";
        short icmpV6Type = getUnsignedByte(data);
        switch (icmpV6Type) {
            case ICMP_V6_TYPE_ECHO_REQUEST:
                mTypeString = "Echo Request";
                return;
            case ICMP_V6_TYPE_ECHO_REPLY:
                mTypeString = "Echo Reply";
                return;
            case ICMP_V6_TYPE_ROUTER_SOLICITATION:
                mTypeString = "Router Solicitation";
                return;
            case ICMP_V6_TYPE_ROUTER_ADVERTISEMENT:
                mTypeString = "Router Advertisement";
                return;
            case ICMP_V6_TYPE_NEIGHBOR_SOLICITATION:
                mTypeString = "Neighbor Solicitation";
                return;
            case ICMP_V6_TYPE_NEIGHBOR_ADVERTISEMENT:
                mTypeString = "Neighbor Advertisement";
                return;
            case ICMP_V6_TYPE_MULTICAST_LISTENER_DISCOVERY:
                mTypeString = "MLDv2 report";
                return;
            default:
                mTypeString = "Type " + icmpV6Type;
                return;
        }
    }

    private static final byte EAPOL_TYPE_KEY = 3;
    private static final byte EAPOL_KEY_DESCRIPTOR_RSN_KEY = 2;
    private static final byte EAPOL_LENGTH_LEN = 2;
    private static final short WPA_KEY_INFO_FLAG_PAIRWISE = (short) 1 << 3;  // bit 4
    private static final short WPA_KEY_INFO_FLAG_INSTALL = (short) 1 << 6;  // bit 7
    private static final short WPA_KEY_INFO_FLAG_MIC = (short) 1 << 8;  // bit 9
    private static final byte WPA_KEYLEN_LEN = 2;
    private static final byte WPA_REPLAY_COUNTER_LEN = 8;
    private static final byte WPA_KEY_NONCE_LEN = 32;
    private static final byte WPA_KEY_IV_LEN = 16;
    private static final byte WPA_KEY_RECEIVE_SEQUENCE_COUNTER_LEN = 8;
    private static final byte WPA_KEY_IDENTIFIER_LEN = 8;
    private static final byte WPA_KEY_MIC_LEN = 16;

    private void parseEapolPacket(ByteBuffer data) {
        mMostSpecificProtocolString = "EAPOL";
        short eapolVersion = getUnsignedByte(data);
        if (eapolVersion < 1 || eapolVersion > 2) {
            Log.e(TAG, "Unrecognized EAPOL version " + eapolVersion);
            return;
        }

        short eapolType = getUnsignedByte(data);
        if (eapolType != EAPOL_TYPE_KEY) {
            Log.e(TAG, "Unrecognized EAPOL type " + eapolType);
            return;
        }

        data.position(data.position() + EAPOL_LENGTH_LEN);
        short eapolKeyDescriptorType = getUnsignedByte(data);
        if (eapolKeyDescriptorType != EAPOL_KEY_DESCRIPTOR_RSN_KEY) {
            Log.e(TAG, "Unrecognized key descriptor " + eapolKeyDescriptorType);
            return;
        }

        short wpaKeyInfo = data.getShort();
        if ((wpaKeyInfo & WPA_KEY_INFO_FLAG_PAIRWISE) == 0) {
            mTypeString = "Group Key";
        } else {
            mTypeString = "Pairwise Key";
        }

        // See goo.gl/tu8AQC for details.
        if ((wpaKeyInfo & WPA_KEY_INFO_FLAG_MIC) == 0) {
            mTypeString += " message 1/4";
            return;
        }

        if ((wpaKeyInfo & WPA_KEY_INFO_FLAG_INSTALL) != 0) {
            mTypeString += " message 3/4";
            return;
        }

        data.position(data.position() + WPA_KEYLEN_LEN + WPA_REPLAY_COUNTER_LEN
                + WPA_KEY_NONCE_LEN + WPA_KEY_IV_LEN + WPA_KEY_RECEIVE_SEQUENCE_COUNTER_LEN
                + WPA_KEY_IDENTIFIER_LEN + WPA_KEY_MIC_LEN);
        int wpaKeyDataLen = getUnsignedShort(data);
        if (wpaKeyDataLen > 0) {
            mTypeString += " message 2/4";
        } else {
            mTypeString += " message 4/4";
        }
    }

    private static final byte IEEE_80211_FRAME_TYPE_MGMT = 0b00;
    // Per 802.11-2016 Table 9-1
    private static final byte IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_ASSOC_REQ = 0b0000;
    private static final byte IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_ASSOC_RESP = 0b0001;
    private static final byte IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_REASSOC_REQ = 0b0010;
    private static final byte IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_REASSOC_RESP = 0b0011;
    private static final byte IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_PROBE_REQ = 0b0100;
    private static final byte IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_PROBE_RESP = 0b0101;
    private static final byte IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_TIMING_AD = 0b0110;
    // 0b0111 reserved
    private static final byte IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_BEACON = 0b1000;
    private static final byte IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_ATIM = 0b1001;
    private static final byte IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_DISASSOC = 0b1010;
    private static final byte IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_AUTH = 0b1011;
    private static final byte IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_DEAUTH = 0b1100;
    private static final byte IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_ACTION = 0b1101;
    private static final byte IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_ACTION_NO_ACK = 0b1110;
    // 0b1111 reserved

    private static final byte IEEE_80211_FRAME_FLAG_ORDER = (byte) (1 << 7); // bit 8
    private static final byte IEEE_80211_DURATION_LEN = 2;
    private static final byte IEEE_80211_ADDR1_LEN = 6;
    private static final byte IEEE_80211_ADDR2_LEN = 6;
    private static final byte IEEE_80211_ADDR3_LEN = 6;
    private static final byte IEEE_80211_SEQUENCE_CONTROL_LEN = 2;
    private static final byte IEEE_80211_HT_CONTROL_LEN = 4;

    private static byte parseIeee80211FrameCtrlVersion(byte b) {
        return (byte) (b & 0b00000011);
    }
    private static byte parseIeee80211FrameCtrlType(byte b) {
        return (byte) ((b & 0b00001100) >> 2);
    }
    private static byte parseIeee80211FrameCtrlSubtype(byte b) {
        return (byte) ((b & 0b11110000) >> 4);
    }
    private void parseManagementFrame(ByteBuffer data) {  // 802.11-2012 Sec 8.3.3.1
        data.order(ByteOrder.LITTLE_ENDIAN);

        mMostSpecificProtocolString = "802.11 Mgmt";
        byte frameControlVersionTypeSubtype = data.get();
        byte ieee80211Version = parseIeee80211FrameCtrlVersion(frameControlVersionTypeSubtype);
        if (ieee80211Version != 0) {
            Log.e(TAG, "Unrecognized 802.11 version " + ieee80211Version);
            return;
        }

        byte ieee80211FrameType = parseIeee80211FrameCtrlType(frameControlVersionTypeSubtype);
        if (ieee80211FrameType != IEEE_80211_FRAME_TYPE_MGMT) {
            Log.e(TAG, "Unexpected frame type " + ieee80211FrameType);
            return;
        }

        byte frameControlFlags = data.get();

        data.position(data.position() + IEEE_80211_DURATION_LEN + IEEE_80211_ADDR1_LEN
                + IEEE_80211_ADDR2_LEN + IEEE_80211_ADDR3_LEN + IEEE_80211_SEQUENCE_CONTROL_LEN);

        if ((frameControlFlags & IEEE_80211_FRAME_FLAG_ORDER) != 0) {
            // Per 802.11-2012 Sec 8.2.4.1.10.
            data.position(data.position() + IEEE_80211_HT_CONTROL_LEN);
        }

        byte ieee80211FrameSubtype = parseIeee80211FrameCtrlSubtype(frameControlVersionTypeSubtype);
        switch (ieee80211FrameSubtype) {
            case IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_ASSOC_REQ:
                mTypeString = "Association Request";
                return;
            case IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_ASSOC_RESP:
                mTypeString = "Association Response";
                parseAssociationResponse(data);
                return;
            case IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_REASSOC_REQ:
                mTypeString = "Reassociation Request";
                return;
            case IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_REASSOC_RESP:
                mTypeString = "Reassociation Response";
                return;
            case IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_PROBE_REQ:
                mTypeString = "Probe Request";
                return;
            case IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_PROBE_RESP:
                mTypeString = "Probe Response";
                return;
            case IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_TIMING_AD:
                mTypeString = "Timing Advertisement";
                return;
            case IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_BEACON:
                mTypeString = "Beacon";
                return;
            case IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_ATIM:
                mTypeString = "ATIM";
                return;
            case IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_DISASSOC:
                mTypeString = "Disassociation";
                parseDisassociationFrame(data);
                return;
            case IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_AUTH:
                mTypeString = "Authentication";
                parseAuthenticationFrame(data);
                return;
            case IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_DEAUTH:
                mTypeString = "Deauthentication";
                parseDeauthenticationFrame(data);
                return;
            case IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_ACTION:
                mTypeString = "Action";
                return;
            case IEEE_80211_FRAME_TYPE_MGMT_SUBTYPE_ACTION_NO_ACK:
                mTypeString = "Action No Ack";
                return;
            case 0b0111:
            case 0b1111:
                mTypeString = "Reserved";
                return;
            default:
                mTypeString = "Unexpected subtype " + ieee80211FrameSubtype;
                return;
        }
    }

    // Per 802.11-2012 Secs 8.3.3.6 and 8.4.1.
    private static final byte IEEE_80211_CAPABILITY_INFO_LEN = 2;
    private void parseAssociationResponse(ByteBuffer data) {
        data.position(data.position() + IEEE_80211_CAPABILITY_INFO_LEN);
        short resultCode = data.getShort();
        mResultString = String.format(
                "%d: %s", resultCode, decodeIeee80211StatusCode(resultCode));
    }

    // Per 802.11-2016 Sec 9.3.3.5
    private void parseDisassociationFrame(ByteBuffer data) {
        short reasonCode = data.getShort();
        mResultString = String.format("%d: %s", reasonCode, decodeIeee80211ReasonCode(reasonCode));
    }

    // Per 802.11-2012 Secs 8.3.3.11 and 8.4.1.
    private static final short IEEE_80211_AUTH_ALG_OPEN = 0;
    private static final short IEEE_80211_AUTH_ALG_SHARED_KEY = 1;
    private static final short IEEE_80211_AUTH_ALG_FAST_BSS_TRANSITION = 2;
    private static final short IEEE_80211_AUTH_ALG_SIMUL_AUTH_OF_EQUALS = 3;
    private void parseAuthenticationFrame(ByteBuffer data) {
        short algorithm = data.getShort();
        short sequenceNum = data.getShort();
        boolean hasResultCode = false;
        switch (algorithm) {
            case IEEE_80211_AUTH_ALG_OPEN:
            case IEEE_80211_AUTH_ALG_SHARED_KEY:
                if (sequenceNum == 2) {
                    hasResultCode = true;
                }
                break;
            case IEEE_80211_AUTH_ALG_FAST_BSS_TRANSITION:
                if (sequenceNum == 2 || sequenceNum == 4) {
                    hasResultCode = true;
                }
                break;
            case IEEE_80211_AUTH_ALG_SIMUL_AUTH_OF_EQUALS:
                hasResultCode = true;
                break;
            default:
                // Ignore unknown algorithm -- don't know which frames would have result codes.
        }

        if (hasResultCode) {
            short resultCode = data.getShort();
            mResultString = String.format(
                    "%d: %s", resultCode, decodeIeee80211StatusCode(resultCode));
        }
    }

    // Per 802.11-2016 Sec 9.3.3.13
    private void parseDeauthenticationFrame(ByteBuffer data) {
        short reasonCode = data.getShort();
        mResultString = String.format("%d: %s", reasonCode, decodeIeee80211ReasonCode(reasonCode));
    }

    // Per 802.11-2016 Table 9-45
    private String decodeIeee80211ReasonCode(short reasonCode) {
        switch (reasonCode) {
            case 0:
                return "Reserved";
            case 1:
                return "Unspecified reason";
            case 2:
                return "Previous authentication no longer valid";
            case 3:
                return "Deauthenticated because sending STA is leaving (or has left) IBSS or ESS";
            case 4:
                return "Disassociated due to inactivity";
            case 5:
                return "Disassociated because AP is unable to handle all currently associated STAs";
            case 6:
                return "Class 2 frame received from nonauthenticated STA";
            case 7:
                return "Class 3 frame received from nonassociated STA";
            case 8:
                return "Disassociated because sending STA is leaving (or has left) BSS";
            case 9:
                return "STA requesting (re)association is not authenticated with responding STA";
            case 10:
                return "Disassociated because the information in the Power Capability element is "
                        + "unacceptable";
            case 11:
                return "Disassociated because the information in the Supported Channels element "
                        + "is unacceptable";
            case 12:
                return "Disassociated due to BSS transition management";
            case 13:
                return "Invalid element, i.e., an element defined in this standard for which the "
                        + "content does not meet the specifications in Clause 9";
            case 14:
                return "Message integrity code (MIC) failure";
            case 15:
                return "4-way handshake timeout";
            case 16:
                return "Group key handshake timeout";
            case 17:
                return "Element in 4-way handshake different from (Re)Association Request/Probe "
                        + "Response/Beacon frame";
            case 18:
                return "Invalid group cipher";
            case 19:
                return "Invalid pairwise cipher";
            case 20:
                return "Invalid AKMP";
            case 21:
                return "Unsupported RSNE version";
            case 22:
                return "Invalid RSNE capabilities";
            case 23:
                return "IEEE 802.1X authentication failed";
            case 24:
                return "Cipher suite rejected because of the security policy";
            case 25:
                return "TDLS direct-link teardown due to TDLS peer STA unreachable via the TDLS "
                        + "direct link";
            case 26:
                return "TDLS direct-link teardown for unspecified reason";
            case 27:
                return "Disassociated because session terminated by SSP request";
            case 28:
                return "Disassociated because of lack of SSP roaming agreement";
            case 29:
                return "Requested service rejected because of SSP cipher suite or AKM requirement";
            case 30:
                return "Requested service not authorized in this location";
            case 31:
                return "TS deleted because QoS AP lacks sufficient bandwidth for this QoS STA due"
                        + " to a change in BSS service characteristics or operational mode (e.g.,"
                        + " an HT BSS change from 40 MHz channel to 20 MHz channel)";
            case 32:
                return "Disassociated for unspecified, QoS-related reason";
            case 33:
                return "Disassociated because QoS AP lacks sufficient bandwidth for this QoS STA";
            case 34:
                return "Disassociated because excessive number of frames need to be acknowledged,"
                        + " but are not acknowledged due to AP transmissions and/or poor channel "
                        + "conditions";
            case 35:
                return "Disassociated because STA is transmitting outside the limits of its TXOPs";
            case 36:
                return "Requesting STA is leaving the BSS (or resetting)";
            case 37:
                return "Requesting STA is no longer using the stream or session";
            case 38:
                return "Requesting STA received frames using a mechanism for which a setup has "
                        + "not been completed";
            case 39:
                return "Requested from peer STA due to timeout";
            case 40:
            case 41:
            case 42:
            case 43:
            case 44:
                return "<unspecified>";
            case 45:
                return "Peer STA does not support the requested cipher suite";
            case 46:
                return "In a DLS Teardown frame: The teardown was initiated by the DLS peer. In a"
                        + " Disassociation frame: Disassociated because authorized access limit "
                        + "reached";
            case 47:
                return "In a DLS Teardown frame: The teardown was initiated by the AP. In a "
                        + "Disassociation frame: Disassociated due to external service "
                        + "requirements";
            case 48:
                return "Invalid FT Action frame count";
            case 49:
                return "Invalid pairwise master key identifier (PMKID)";
            case 50:
                return "Invalid MDE";
            case 51:
                return "Invalid FTE";
            case 52:
                return "Mesh peering canceled for unknown reasons";
            case 53:
                return "The mesh STA has reached the supported maximum number of peer mesh STAs";
            case 54:
                return "The received information violates the Mesh Configuration policy "
                        + "configured in the mesh STA profile";
            case 55:
                return "The mesh STA has received a Mesh Peering Close frame requesting to close "
                        + "the mesh peering.";
            case 56:
                return "The mesh STA has resent dot11MeshMaxRetries Mesh Peering Open frames, "
                        + "without receiving a Mesh Peering Confirm frame.";
            case 57:
                return "The confirmTimer for the mesh peering instance times out.";
            case 58:
                return "The mesh STA fails to unwrap the GTK or the values in the wrapped "
                        + "contents do not match";
            case 59:
                return "The mesh STA receives inconsistent information about the mesh parameters "
                        + "between mesh peering Management frames";
            case 60:
                return "The mesh STA fails the authenticated mesh peering exchange because due to"
                        + " failure in selecting either the pairwise ciphersuite or group "
                        + "ciphersuite";
            case 61:
                return "The mesh STA does not have proxy information for this external "
                        + "destination.";
            case 62:
                return "The mesh STA does not have forwarding information for this destination.";
            case 63:
                return "The mesh STA determines that the link to the next hop of an active path "
                        + "in its forwarding information is no longer usable.";
            case 64:
                return "The Deauthentication frame was sent because the MAC address of the STA "
                        + "already exists in the mesh BSS. See 11.3.6.";
            case 65:
                return "The mesh STA performs channel switch to meet regulatory requirements.";
            case 66:
                return "The mesh STA performs channel switching with unspecified reason.";
            default:
                return "Reserved";
        }
    }

    // Per 802.11-2012 Table 8-37.
    private String decodeIeee80211StatusCode(short statusCode) {
        switch (statusCode) {
            case 0:
                return "Success";
            case 1:
                return "Unspecified failure";
            case 2:
                return "TDLS wakeup schedule rejected; alternative provided";
            case 3:
                return "TDLS wakeup schedule rejected";
            case 4:
                return "Reserved";
            case 5:
                return "Security disabled";
            case 6:
                return "Unacceptable lifetime";
            case 7:
                return "Not in same BSS";
            case 8:
            case 9:
                return "Reserved";
            case 10:
                return "Capabilities mismatch";
            case 11:
                return "Reassociation denied; could not confirm association exists";
            case 12:
                return "Association denied for reasons outside standard";
            case 13:
                return "Unsupported authentication algorithm";
            case 14:
                return "Authentication sequence number of of sequence";
            case 15:
                return "Authentication challenge failure";
            case 16:
                return "Authentication timeout";
            case 17:
                return "Association denied; too many STAs";
            case 18:
                return "Association denied; must support BSSBasicRateSet";
            case 19:
                return "Association denied; must support short preamble";
            case 20:
                return "Association denied; must support PBCC";
            case 21:
                return "Association denied; must support channel agility";
            case 22:
                return "Association rejected; must support spectrum management";
            case 23:
                return "Association rejected; unacceptable power capability";
            case 24:
                return "Association rejected; unacceptable supported channels";
            case 25:
                return "Association denied; must support short slot time";
            case 26:
                return "Association denied; must support DSSS-OFDM";
            case 27:
                return "Association denied; must support HT";
            case 28:
                return "R0 keyholder unreachable (802.11r)";
            case 29:
                return "Association denied; must support PCO transition time";
            case 30:
                return "Refused temporarily";
            case 31:
                return "Robust management frame policy violation";
            case 32:
                return "Unspecified QoS failure";
            case 33:
                return "Association denied; insufficient bandwidth for QoS";
            case 34:
                return "Association denied; poor channel";
            case 35:
                return "Association denied; must support QoS";
            case 36:
                return "Reserved";
            case 37:
                return "Declined";
            case 38:
                return "Invalid parameters";
            case 39:
                return "TS cannot be honored; changes suggested";
            case 40:
                return "Invalid element";
            case 41:
                return "Invalid group cipher";
            case 42:
                return "Invalid pairwise cipher";
            case 43:
                return "Invalid auth/key mgmt proto (AKMP)";
            case 44:
                return "Unsupported RSNE version";
            case 45:
                return "Invalid RSNE capabilities";
            case 46:
                return "Cipher suite rejected by policy";
            case 47:
                return "TS cannot be honored now; try again later";
            case 48:
                return "Direct link rejected by policy";
            case 49:
                return "Destination STA not in BSS";
            case 50:
                return "Destination STA not configured for QoS";
            case 51:
                return "Association denied; listen interval too large";
            case 52:
                return "Invalid fast transition action frame count";
            case 53:
                return "Invalid PMKID";
            case 54:
                return "Invalid MDE";
            case 55:
                return "Invalid FTE";
            case 56:
                return "Unsupported TCLAS";
            case 57:
                return "Requested TCLAS exceeds resources";
            case 58:
                return "TS cannot be honored; try another BSS";
            case 59:
                return "GAS Advertisement not supported";
            case 60:
                return "No outstanding GAS request";
            case 61:
                return "No query response from GAS server";
            case 62:
                return "GAS query timeout";
            case 63:
                return "GAS response too large";
            case 64:
                return "Home network does not support request";
            case 65:
                return "Advertisement server unreachable";
            case 66:
                return "Reserved";
            case 67:
                return "Rejected for SSP permissions";
            case 68:
                return "Authentication required";
            case 69:
            case 70:
            case 71:
                return "Reserved";
            case 72:
                return "Invalid RSNE contents";
            case 73:
                return "U-APSD coexistence unsupported";
            case 74:
                return "Requested U-APSD coex mode unsupported";
            case 75:
                return "Requested parameter unsupported with U-APSD coex";
            case 76:
                return "Auth rejected; anti-clogging token required";
            case 77:
                return "Auth rejected; offered group is not supported";
            case 78:
                return "Cannot find alternative TBTT";
            case 79:
                return "Transmission failure";
            case 80:
                return "Requested TCLAS not supported";
            case 81:
                return "TCLAS resources exhausted";
            case 82:
                return "Rejected with suggested BSS transition";
            case 83:
                return "Reserved";
            case 84:
            case 85:
            case 86:
            case 87:
            case 88:
            case 89:
            case 90:
            case 91:
                return "<unspecified>";
            case 92:
                return "Refused due to external reason";
            case 93:
                return "Refused; AP out of memory";
            case 94:
                return "Refused; emergency services not supported";
            case 95:
                return "GAS query response outstanding";
            case 96:
            case 97:
            case 98:
            case 99:
                return "Reserved";
            case 100:
                return "Failed; reservation conflict";
            case 101:
                return "Failed; exceeded MAF limit";
            case 102:
                return "Failed; exceeded MCCA track limit";
            default:
                return "Reserved";
        }
    }
}
