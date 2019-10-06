/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.ike.ikev2;

import android.annotation.IntDef;
import android.util.ArraySet;

import com.android.ike.ikev2.exceptions.InvalidSyntaxException;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

/**
 * IkeTrafficSelector represents a Traffic Selector of a Child SA.
 *
 * <p>IkeTrafficSelector can be constructed by users for initiating Create Child exchange or be
 * constructed from a decoded inbound Traffic Selector Payload.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.13">RFC 7296, Internet Key Exchange
 *     Protocol Version 2 (IKEv2)</a>
 */
public final class IkeTrafficSelector {

    // IpProtocolId consists of standard IP Protocol IDs.
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({IP_PROTOCOL_ID_UNSPEC, IP_PROTOCOL_ID_ICMP, IP_PROTOCOL_ID_TCP, IP_PROTOCOL_ID_UCP})
    public @interface IpProtocolId {}

    // Zero value is re-defined by IKE to indicate that all IP protocols are acceptable.
    @VisibleForTesting static final int IP_PROTOCOL_ID_UNSPEC = 0;
    @VisibleForTesting static final int IP_PROTOCOL_ID_ICMP = 1;
    @VisibleForTesting static final int IP_PROTOCOL_ID_TCP = 6;
    @VisibleForTesting static final int IP_PROTOCOL_ID_UCP = 17;

    private static final ArraySet<Integer> IP_PROTOCOL_ID_SET = new ArraySet<>();

    static {
        IP_PROTOCOL_ID_SET.add(IP_PROTOCOL_ID_UNSPEC);
        IP_PROTOCOL_ID_SET.add(IP_PROTOCOL_ID_ICMP);
        IP_PROTOCOL_ID_SET.add(IP_PROTOCOL_ID_TCP);
        IP_PROTOCOL_ID_SET.add(IP_PROTOCOL_ID_UCP);
    }

    /**
     * TrafficSelectorType consists of IKE standard Traffic Selector Types.
     *
     * @see <a
     *     href="https://www.iana.org/assignments/ikev2-parameters/ikev2-parameters.xhtml">Internet
     *     Key Exchange Version 2 (IKEv2) Parameters</a>
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({TRAFFIC_SELECTOR_TYPE_IPV4_ADDR_RANGE, TRAFFIC_SELECTOR_TYPE_IPV6_ADDR_RANGE})
    public @interface TrafficSelectorType {}

    public static final int TRAFFIC_SELECTOR_TYPE_IPV4_ADDR_RANGE = 7;
    public static final int TRAFFIC_SELECTOR_TYPE_IPV6_ADDR_RANGE = 8;

    // TODO: Consider defining these constants in a central place in Connectivity.
    private static final int IPV4_ADDR_LEN = 4;
    private static final int IPV6_ADDR_LEN = 16;

    @VisibleForTesting static final int TRAFFIC_SELECTOR_IPV4_LEN = 16;
    @VisibleForTesting static final int TRAFFIC_SELECTOR_IPV6_LEN = 40;

    public final int tsType;
    public final int ipProtocolId;
    public final int selectorLength;
    public final int startPort;
    public final int endPort;
    public final InetAddress startingAddress;
    public final InetAddress endingAddress;

    private IkeTrafficSelector(
            int tsType,
            int ipProtocolId,
            int selectorLength,
            int startPort,
            int endPort,
            InetAddress startingAddress,
            InetAddress endingAddress) {
        this.tsType = tsType;
        this.ipProtocolId = ipProtocolId;
        this.selectorLength = selectorLength;
        this.startPort = startPort;
        this.endPort = endPort;
        this.startingAddress = startingAddress;
        this.endingAddress = endingAddress;
    }

    // TODO: Add a constructor for users to construct IkeTrafficSelector.

    /**
     * Decode IkeTrafficSelectors from inbound Traffic Selector Payload.
     *
     * <p>This method is only called by IkeTsPayload when decoding inbound IKE message.
     *
     * @param numTs number or Traffic Selectors
     * @param tsBytes encoded byte array of Traffic Selectors
     * @return an array of decoded IkeTrafficSelectors
     * @throws InvalidSyntaxException if received bytes are malformed.
     */
    public static IkeTrafficSelector[] decodeIkeTrafficSelectors(int numTs, byte[] tsBytes)
            throws InvalidSyntaxException {
        IkeTrafficSelector[] tsArray = new IkeTrafficSelector[numTs];
        ByteBuffer inputBuffer = ByteBuffer.wrap(tsBytes);

        try {
            for (int i = 0; i < numTs; i++) {
                int tsType = Byte.toUnsignedInt(inputBuffer.get());
                switch (tsType) {
                    case TRAFFIC_SELECTOR_TYPE_IPV4_ADDR_RANGE:
                        tsArray[i] = decodeIpv4TrafficSelector(inputBuffer);
                        break;
                    case TRAFFIC_SELECTOR_TYPE_IPV6_ADDR_RANGE:
                        // TODO: Support it.
                        throw new UnsupportedOperationException("Cannot decode this type.");
                    default:
                        throw new InvalidSyntaxException(
                                "Invalid Traffic Selector type: " + tsType);
                }
            }
        } catch (BufferOverflowException e) {
            // Throw exception if any Traffic Selector has invalid length.
            throw new InvalidSyntaxException(e);
        }

        if (inputBuffer.remaining() != 0) {
            throw new InvalidSyntaxException(
                    "Unexpected trailing characters of Traffic Selectors.");
        }

        return tsArray;
    }

    // Decode Traffic Selector using IPv4 address range from a ByteBuffer. A BufferOverflowException
    // will be thrown and caught by method caller if operation reaches the input ByteBuffer's limit.
    private static IkeTrafficSelector decodeIpv4TrafficSelector(ByteBuffer inputBuffer)
            throws InvalidSyntaxException {
        // Decode and validate IP Protocol ID
        int ipProtocolId = Byte.toUnsignedInt(inputBuffer.get());
        if (!IP_PROTOCOL_ID_SET.contains(ipProtocolId)) {
            throw new InvalidSyntaxException("Invalid IP Protocol ID.");
        }

        // Decode and validate Selector Length
        int tsLength = Short.toUnsignedInt(inputBuffer.getShort());
        if (TRAFFIC_SELECTOR_IPV4_LEN != tsLength) {
            throw new InvalidSyntaxException("Invalid Traffic Selector Length.");
        }

        // Decode and validate ports
        int startPort = Short.toUnsignedInt(inputBuffer.getShort());
        int endPort = Short.toUnsignedInt(inputBuffer.getShort());
        if (startPort > endPort) {
            throw new InvalidSyntaxException("Received invalid port range.");
        }

        // Decode and validate IPv4 addresses
        byte[] startAddressBytes = new byte[IPV4_ADDR_LEN];
        byte[] endAddressBytes = new byte[IPV4_ADDR_LEN];
        inputBuffer.get(startAddressBytes);
        inputBuffer.get(endAddressBytes);
        try {
            Inet4Address startAddress =
                    (Inet4Address) (Inet4Address.getByAddress(startAddressBytes));
            Inet4Address endAddress = (Inet4Address) (Inet4Address.getByAddress(endAddressBytes));

            // Validate address range.
            if (!isInetAddressRangeValid(startAddress, endAddress)) {
                throw new InvalidSyntaxException("Received invalid IPv4 address range.");
            }

            return new IkeTrafficSelector(
                    TRAFFIC_SELECTOR_TYPE_IPV4_ADDR_RANGE,
                    ipProtocolId,
                    TRAFFIC_SELECTOR_IPV4_LEN,
                    startPort,
                    endPort,
                    startAddress,
                    endAddress);
        } catch (ClassCastException | UnknownHostException | IllegalArgumentException e) {
            throw new InvalidSyntaxException(e);
        }
    }

    // TODO: Add a method for decoding IPv6 traffic selector.

    // Validate address range. Caller must ensure two address are same types.
    // TODO: Consider moving it to the platform code in the future.
    private static boolean isInetAddressRangeValid(
            InetAddress startAddress, InetAddress endAddress) {
        byte[] startAddrBytes = startAddress.getAddress();
        byte[] endAddrBytes = endAddress.getAddress();

        if (startAddrBytes.length != endAddrBytes.length) {
            throw new IllegalArgumentException("Two addresses are different types.");
        }

        for (int i = 0; i < startAddrBytes.length; i++) {
            int unsignedByteStart = Byte.toUnsignedInt(startAddrBytes[i]);
            int unsignedByteEnd = Byte.toUnsignedInt(endAddrBytes[i]);

            if (unsignedByteStart < unsignedByteEnd) {
                return true;
            } else if (unsignedByteStart > unsignedByteEnd) {
                return false;
            }
        }
        return true;
    }
}
