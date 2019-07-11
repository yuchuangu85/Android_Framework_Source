package com.android.server.wifi.hotspot2.anqp;

import com.android.internal.annotations.VisibleForTesting;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Operator Friendly Name vendor specific ANQP Element,
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.3.
 *
 * Format:
 *
 * | Operator Name Duple #1 (optional) | ...
 *          variable
 *
 * | Operator Name Duple #N (optional) |
 *             variable
 */
public class HSFriendlyNameElement extends ANQPElement {
    /**
     * Maximum length for an Operator Name.  Refer to Hotspot 2.0 (Release 2) Technical
     * Specification section 4.3 for more info.
     */
    @VisibleForTesting
    public static final int MAXIMUM_OPERATOR_NAME_LENGTH = 252;

    private final List<I18Name> mNames;

    @VisibleForTesting
    public HSFriendlyNameElement(List<I18Name> names) {
        super(Constants.ANQPElementType.HSFriendlyName);
        mNames = names;
    }

    /**
     * Parse a HSFriendlyNameElement from the given buffer.
     *
     * @param payload The buffer to read from
     * @return {@link HSFriendlyNameElement}
     * @throws BufferUnderflowException
     * @throws ProtocolException
     */
    public static HSFriendlyNameElement parse(ByteBuffer payload)
            throws ProtocolException {
        List<I18Name> names = new ArrayList<I18Name>();
        while (payload.hasRemaining()) {
            I18Name name = I18Name.parse(payload);
            // Verify that the number of bytes for the operator name doesn't exceed the max
            // allowed.
            int textBytes = name.getText().getBytes(StandardCharsets.UTF_8).length;
            if (textBytes > MAXIMUM_OPERATOR_NAME_LENGTH) {
                throw new ProtocolException("Operator Name exceeds the maximum allowed "
                        + textBytes);
            }
            names.add(name);
        }
        return new HSFriendlyNameElement(names);
    }

    public List<I18Name> getNames() {
        return Collections.unmodifiableList(mNames);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof HSFriendlyNameElement)) {
            return false;
        }
        HSFriendlyNameElement that = (HSFriendlyNameElement) thatObject;
        return mNames.equals(that.mNames);
    }

    @Override
    public int hashCode() {
        return mNames.hashCode();
    }

    @Override
    public String toString() {
        return "HSFriendlyName{" +
                "mNames=" + mNames +
                '}';
    }
}
