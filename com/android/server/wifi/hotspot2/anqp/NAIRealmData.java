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
import com.android.server.wifi.hotspot2.anqp.eap.EAPMethod;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The NAI Realm Data ANQP sub-element, IEEE802.11-2012 section 8.4.4.10 figure 8-418.
 *
 * Format:
 * | Length | Encoding | NAIRealm Length | NAIRealm | EAPMethod Count | EAPMethod #1 (optional) |
 *     2         1               1         variable          1                  variable
 */
public class NAIRealmData {
    /**
     * Mask for determining NAI Realm String encoding type.
     */
    @VisibleForTesting
    public static final int NAI_ENCODING_UTF8_MASK = 0x1;

    @VisibleForTesting
    public static final String NAI_REALM_STRING_SEPARATOR = ";";

    private final List<String> mRealms;
    private final List<EAPMethod> mEAPMethods;

    @VisibleForTesting
    public NAIRealmData(List<String> realms, List<EAPMethod> eapMethods) {
        mRealms = realms;
        mEAPMethods = eapMethods;
    }

    /**
     * Parse a NAIRealmData from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @return {@link NAIRealmElement}
     * @throws BufferUnderflowException
     * @throws ProtocolException
     */
    public static NAIRealmData parse(ByteBuffer payload) throws ProtocolException {
        // Read and verify the length field.
        int length = (int) ByteBufferReader.readInteger(payload, ByteOrder.LITTLE_ENDIAN, 2)
                & 0xFFFF;
        if (length > payload.remaining()) {
            throw new ProtocolException("Invalid data length: " + length);
        }

        // Read the encoding field.
        boolean utf8 = (payload.get() & NAI_ENCODING_UTF8_MASK) != 0;

        // Read the realm string.
        String realm = ByteBufferReader.readStringWithByteLength(
                payload, utf8 ? StandardCharsets.UTF_8 : StandardCharsets.US_ASCII);
        List<String> realmList = Arrays.asList(realm.split(NAI_REALM_STRING_SEPARATOR));

        // Read the EAP methods.
        int methodCount = payload.get() & 0xFF;
        List<EAPMethod> eapMethodList = new ArrayList<>();
        while (methodCount > 0) {
            eapMethodList.add(EAPMethod.parse(payload));
            methodCount--;
        }
        return new NAIRealmData(realmList, eapMethodList);
    }

    public List<String> getRealms() {
        return Collections.unmodifiableList(mRealms);
    }

    public List<EAPMethod> getEAPMethods() {
        return Collections.unmodifiableList(mEAPMethods);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof NAIRealmData)) {
            return false;
        }
        NAIRealmData that = (NAIRealmData) thatObject;
        return mRealms.equals(that.mRealms) && mEAPMethods.equals(that.mEAPMethods);
    }

    @Override
    public int hashCode() {
        return mRealms.hashCode() * 31 + mEAPMethods.hashCode();
    }

    @Override
    public String toString() {
        return "NAIRealmElement{mRealms=" + mRealms + " mEAPMethods=" + mEAPMethods + "}";
    }
}
