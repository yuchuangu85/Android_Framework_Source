package com.android.server.wifi.hotspot2.anqp;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.ByteBufferReader;
import com.android.server.wifi.hotspot2.Utils;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Roaming Consortium ANQP Element, IEEE802.11-2012 section 8.4.4.7
 *
 ** Format:
 *
 * | OI Duple #1 (optional) | ...
 *         variable
 *
 * | OI Length |     OI     |
 *       1        variable
 *
 */
public class RoamingConsortiumElement extends ANQPElement {
    @VisibleForTesting
    public static final int MINIMUM_OI_LENGTH = Byte.BYTES;

    @VisibleForTesting
    public static final int MAXIMUM_OI_LENGTH = Long.BYTES;

    private final List<Long> mOIs;

    @VisibleForTesting
    public RoamingConsortiumElement(List<Long> ois) {
        super(Constants.ANQPElementType.ANQPRoamingConsortium);
        mOIs = ois;
    }

    /**
     * Parse a VenueNameElement from the given payload.
     *
     * @param payload The byte buffer to read from
     * @return {@link RoamingConsortiumElement}
     * @throws BufferUnderflowException
     * @throws ProtocolException
     */
    public static RoamingConsortiumElement parse(ByteBuffer payload)
            throws ProtocolException {
        List<Long> OIs = new ArrayList<Long>();
        while (payload.hasRemaining()) {
            int length = payload.get() & 0xFF;
            if (length < MINIMUM_OI_LENGTH || length > MAXIMUM_OI_LENGTH) {
                throw new ProtocolException("Bad OI length: " + length);
            }
            OIs.add(ByteBufferReader.readInteger(payload, ByteOrder.BIG_ENDIAN, length));
        }
        return new RoamingConsortiumElement(OIs);
    }

    public List<Long> getOIs() {
        return Collections.unmodifiableList(mOIs);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof RoamingConsortiumElement)) {
            return false;
        }
        RoamingConsortiumElement that = (RoamingConsortiumElement) thatObject;
        return mOIs.equals(that.mOIs);
    }

    @Override
    public int hashCode() {
        return mOIs.hashCode();
    }

    @Override
    public String toString() {
        return "RoamingConsortium{mOis=[" + Utils.roamingConsortiumsToString(mOIs) + "]}";
    }
}
