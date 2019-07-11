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
import com.android.server.wifi.ByteBufferReader;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Domain Name ANQP Element, IEEE802.11-2012 section 8.4.4.15.
 *
 * Format:
 * | Domain Name Field #1 (optional) | ...
 *            variable
 *
 * Domain Name Field Format:
 * | Length | Domain Name |
 *      1       variable
 */
public class DomainNameElement extends ANQPElement {
    private final List<String> mDomains;

    @VisibleForTesting
    public DomainNameElement(List<String> domains) {
        super(Constants.ANQPElementType.ANQPDomName);
        mDomains = domains;
    }

    /**
     * Parse a DomainNameElement from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @return {@link DomainNameElement}
     * @throws BufferUnderflowException
     */
    public static DomainNameElement parse(ByteBuffer payload) {
        List<String> domains = new ArrayList<>();
        while (payload.hasRemaining()) {
            // Use latin-1 to decode for now - safe for ASCII and retains encoding
            domains.add(ByteBufferReader.readStringWithByteLength(
                    payload, StandardCharsets.ISO_8859_1));
        }
        return new DomainNameElement(domains);
    }

    public List<String> getDomains() {
        return Collections.unmodifiableList(mDomains);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof DomainNameElement)) {
            return false;
        }
        DomainNameElement that = (DomainNameElement) thatObject;
        return mDomains.equals(that.mDomains);
    }

    @Override
    public int hashCode() {
        return mDomains.hashCode();
    }

    @Override
    public String toString() {
        return "DomainName{" +
                "mDomains=" + mDomains +
                '}';
    }
}
