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

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * The NAI (Network Access Identifier) Realm ANQP Element, IEEE802.11-2012 section 8.4.4.10.
 *
 * Format:
 * | NAI Realm Count (optional) | NAI Realm Data #1 (optional) | ....
 *             2                         variable
 */
public class NAIRealmElement extends ANQPElement {
    private final List<NAIRealmData> mRealmDataList;

    @VisibleForTesting
    public NAIRealmElement(List<NAIRealmData> realmDataList) {
        super(Constants.ANQPElementType.ANQPNAIRealm);
        mRealmDataList = realmDataList;
    }

    /**
     * Parse a NAIRealmElement from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @return {@link NAIRealmElement}
     * @throws BufferUnderflowException
     */
    public static NAIRealmElement parse(ByteBuffer payload)
            throws ProtocolException {
        List<NAIRealmData> realmDataList = new ArrayList<>();
        if (payload.hasRemaining()) {
            int count = (int) ByteBufferReader.readInteger(payload, ByteOrder.LITTLE_ENDIAN, 2)
                    & 0xFFFF;
            while (count > 0) {
                realmDataList.add(NAIRealmData.parse(payload));
                count--;
            }
        }
        return new NAIRealmElement(realmDataList);
    }

    public List<NAIRealmData> getRealmDataList() {
        return Collections.unmodifiableList(mRealmDataList);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof NAIRealmElement)) {
            return false;
        }
        NAIRealmElement that = (NAIRealmElement) thatObject;
        return mRealmDataList.equals(that.mRealmDataList);
    }

    @Override
    public int hashCode() {
        return mRealmDataList.hashCode();
    }

    @Override
    public String toString() {
        return "NAIRealmElement{mRealmDataList=" + mRealmDataList + "}";
    }
}
