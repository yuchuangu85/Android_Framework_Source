package com.android.server.wifi.hotspot2.anqp;

import com.android.internal.annotations.VisibleForTesting;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * The IP Address Type availability ANQP Element, IEEE802.11-2012 section 8.4.4.9
 *
 * Format:
 *
 * | IP Address |
 *       1
 * b0                           b7
 * | IPv6 Address | IPv4 Address |
 *     2 bits          6 bits
 *
 * IPv4 Address field values:
 * 0 - Address type not available
 * 1 - Public IPv4 address available
 * 2 - Port-restricted IPv4 address available
 * 3 - Single NATed private IPv4 address available
 * 4 - Single NATed private IPv4 address available
 * 5 - Port-restricted IPv4 address and single NATed IPv4 address available
 * 6 - Port-restricted IPv4 address and double NATed IPv4 address available
 * 7 - Availability of the address type is not known
 *
 * IPv6 Address field values:
 * 0 - Address type not available
 * 1 - Address type not available
 * 2 - Availability of the address type not known
 *
 */
public class IPAddressTypeAvailabilityElement extends ANQPElement {
    @VisibleForTesting
    public static final int EXPECTED_BUFFER_LENGTH = 1;

    /**
     * Constants for IPv4 availability.
     */
    public static final int IPV4_NOT_AVAILABLE = 0;
    public static final int IPV4_PUBLIC = 1;
    public static final int IPV4_PORT_RESTRICTED = 2;
    public static final int IPV4_SINGLE_NAT = 3;
    public static final int IPV4_DOUBLE_NAT = 4;
    public static final int IPV4_PORT_RESTRICTED_AND_SINGLE_NAT = 5;
    public static final int IPV4_PORT_RESTRICTED_AND_DOUBLE_NAT = 6;
    public static final int IPV4_UNKNOWN = 7;
    private static final Set<Integer> IPV4_AVAILABILITY = new HashSet<Integer>();
    static {
        IPV4_AVAILABILITY.add(IPV4_NOT_AVAILABLE);
        IPV4_AVAILABILITY.add(IPV4_PUBLIC);
        IPV4_AVAILABILITY.add(IPV4_PORT_RESTRICTED);
        IPV4_AVAILABILITY.add(IPV4_SINGLE_NAT);
        IPV4_AVAILABILITY.add(IPV4_DOUBLE_NAT);
        IPV4_AVAILABILITY.add(IPV4_PORT_RESTRICTED_AND_SINGLE_NAT);
        IPV4_AVAILABILITY.add(IPV4_PORT_RESTRICTED_AND_DOUBLE_NAT);
    }

    /**
     * Constants for IPv6 availability.
     */
    public static final int IPV6_NOT_AVAILABLE = 0;
    public static final int IPV6_AVAILABLE = 1;
    public static final int IPV6_UNKNOWN = 2;
    private static final Set<Integer> IPV6_AVAILABILITY = new HashSet<Integer>();
    static {
        IPV6_AVAILABILITY.add(IPV6_NOT_AVAILABLE);
        IPV6_AVAILABILITY.add(IPV6_AVAILABLE);
        IPV6_AVAILABILITY.add(IPV6_UNKNOWN);
    }

    private static final int IPV4_AVAILABILITY_MASK = 0x3F;
    private static final int IPV6_AVAILABILITY_MASK = 0x3;

    private final int mV4Availability;
    private final int mV6Availability;

    @VisibleForTesting
    public IPAddressTypeAvailabilityElement(int v4Availability, int v6Availability) {
        super(Constants.ANQPElementType.ANQPIPAddrAvailability);
        mV4Availability = v4Availability;
        mV6Availability = v6Availability;
    }

    /**
     * Parse an IPAddressTypeAvailabilityElement from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @return {@link IPAddressTypeAvailabilityElement}
     * @throws ProtocolException
     */
    public static IPAddressTypeAvailabilityElement parse(ByteBuffer payload)
            throws ProtocolException {
        if (payload.remaining() != EXPECTED_BUFFER_LENGTH) {
            throw new ProtocolException("Unexpected buffer length: " + payload.remaining());
        }

        int ipField = payload.get() & 0xFF;

        int v6Availability = ipField & IPV6_AVAILABILITY_MASK;
        if (!IPV6_AVAILABILITY.contains(v6Availability)) {
            v6Availability = IPV6_UNKNOWN;
        }

        int v4Availability = (ipField >> 2) & IPV4_AVAILABILITY_MASK;
        if (!IPV4_AVAILABILITY.contains(v4Availability)) {
            v4Availability = IPV4_UNKNOWN;
        }

        return new IPAddressTypeAvailabilityElement(v4Availability, v6Availability);
    }

    public int getV4Availability() {
        return mV4Availability;
    }

    public int getV6Availability() {
        return mV6Availability;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof IPAddressTypeAvailabilityElement)) {
            return false;
        }
        IPAddressTypeAvailabilityElement that = (IPAddressTypeAvailabilityElement) thatObject;
        return mV4Availability == that.mV4Availability && mV6Availability == that.mV6Availability;
    }

    @Override
    public int hashCode() {
        return mV4Availability << 2 + mV6Availability;
    }

    @Override
    public String toString() {
        return "IPAddressTypeAvailability{" +
                "mV4Availability=" + mV4Availability +
                ", mV6Availability=" + mV6Availability +
                '}';
    }
}
