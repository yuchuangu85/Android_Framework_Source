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
 * The Venue Name ANQP Element, IEEE802.11-2012 section 8.4.4.4.
 *
 * Format:
 *
 * | Venue Info | Venue Name Duple #1 (optional) | ...
 *      2                  variable
 *
 * | Venue Name Duple #N (optional) |
 *             variable
 *
 * Refer to {@link I18Name} for the format of the Venue Name Duple
 * fields.
 */
public class VenueNameElement extends ANQPElement {
    @VisibleForTesting
    public static final int VENUE_INFO_LENGTH = 2;

    /**
     * Maximum length for a Venue Name.  Refer to IEEE802.11-2012 section 8.4.4.4 for more info.
     */
    @VisibleForTesting
    public static final int MAXIMUM_VENUE_NAME_LENGTH = 252;

    private final List<I18Name> mNames;

    @VisibleForTesting
    public VenueNameElement(List<I18Name> names) {
        super(Constants.ANQPElementType.ANQPVenueName);
        mNames = names;
    }

    /**
     * Parse a VenueNameElement from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @return {@link VenueNameElement}
     * @throws BufferUnderflowException
     * @throws ProtocolException
     */
    public static VenueNameElement parse(ByteBuffer payload)
            throws ProtocolException {
        // Skip the Venue Info field, which we don't use.
        for (int i = 0; i < VENUE_INFO_LENGTH; ++i) {
            payload.get();
        }

        List<I18Name> names = new ArrayList<I18Name>();
        while (payload.hasRemaining()) {
            I18Name name = I18Name.parse(payload);
            // Verify that the number of octets for the venue name doesn't exceed the max allowed.
            int textBytes = name.getText().getBytes(StandardCharsets.UTF_8).length;
            if (textBytes > MAXIMUM_VENUE_NAME_LENGTH) {
                throw new ProtocolException("Venue Name exceeds the maximum allowed " + textBytes);
            }
            names.add(name);
        }
        return new VenueNameElement(names);
    }

    public List<I18Name> getNames() {
        return Collections.unmodifiableList(mNames);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof VenueNameElement)) {
            return false;
        }
        VenueNameElement that = (VenueNameElement) thatObject;
        return mNames.equals(that.mNames);
    }

    @Override
    public int hashCode() {
        return mNames.hashCode();
    }

    @Override
    public String toString() {
        return "VenueName{ mNames=" + mNames + "}";
    }

}
