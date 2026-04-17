/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.net.module.util;

import android.annotation.NonNull;
import android.net.DnsResolver;

import com.android.net.module.util.DnsPacket.DnsRecord;
import com.android.net.module.util.DnsPacket.ParseException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A specialized {@link DnsPacket} parser for extracting HTTPS DNS records.
 *
 * <p>Instances of this class are created from a raw DNS response byte array. The parsing
 * logic within this class focuses on locating and interpreting DNS records of
 * {@link DnsResolver.TYPE_HTTPS}.
 *
 * <p>See RFC 9460 for more details.
 *
 * @hide
 */
public class DnsHttpsPacket extends DnsPacket {
    private static final String TAG = DnsHttpsPacket.class.getSimpleName();
    // TODO(b/419020719): replace this with a reference to DnsResolver.TYPE_HTTPS once the constant
    // is no longer flagged
    public static final int TYPE_HTTPS = 65;
    private final List<DnsHttpsRecord> mHttpsRecords = new ArrayList<>();

    /**
     * Creates a DnsHttpsPacket object from the given wire-format DNS packet.
     *
     * @hide
     */
    public DnsHttpsPacket(@NonNull byte[] data) throws ParseException {
        super(data);

        int questionsCount = mHeader.getRecordCount(QDSECTION);
        if (questionsCount != 1) {
            throw new ParseException("Unexpected question count: " + questionsCount);
        }

        int queryType = getRecords(QDSECTION).get(0).nsType;
        if (queryType != TYPE_HTTPS) {
            throw new ParseException("Unexpected query type: " + queryType);
        }

        for (DnsRecord record : getRecords(ANSECTION)) {
            if (record instanceof DnsHttpsRecord) {
                final DnsHttpsRecord httpsRecord = (DnsHttpsRecord) record;
                mHttpsRecords.add(httpsRecord);
            }
        }
    }

    /**
     * Returns all the HTTPS records contained in the DNS packet.
     *
     * @hide
     */
    public @NonNull List<DnsHttpsRecord> getAllRecords() {
        return Collections.unmodifiableList(mHttpsRecords);
    }
}
