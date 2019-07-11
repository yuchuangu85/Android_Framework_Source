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

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Connection Capability vendor specific ANQP Element,
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.5
 *
 * Format:
 * | ProtoPort Tuple #1 (optiional) | ....
 *                4
 */
public class HSConnectionCapabilityElement extends ANQPElement {
    private final List<ProtocolPortTuple> mStatusList;

    @VisibleForTesting
    public HSConnectionCapabilityElement(List<ProtocolPortTuple> statusList) {
        super(Constants.ANQPElementType.HSConnCapability);
        mStatusList = statusList;
    }

    /**
     * Parse a HSConnectionCapabilityElement from the given buffer.
     *
     * @param payload The byte buffer to read from
     * @return {@link HSConnectionCapabilityElement}
     * @throws BufferUnderflowException
     */
    public static HSConnectionCapabilityElement parse(ByteBuffer payload) {
        List<ProtocolPortTuple> statusList = new ArrayList<>();
        while (payload.hasRemaining()) {
            statusList.add(ProtocolPortTuple.parse(payload));
        }
        return new HSConnectionCapabilityElement(statusList);
    }

    public List<ProtocolPortTuple> getStatusList() {
        return Collections.unmodifiableList(mStatusList);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof HSConnectionCapabilityElement)) {
            return false;
        }
        HSConnectionCapabilityElement that = (HSConnectionCapabilityElement) thatObject;
        return mStatusList.equals(that.mStatusList);
    }

    @Override
    public int hashCode() {
        return mStatusList.hashCode();
    }

    @Override
    public String toString() {
        return "HSConnectionCapability{" +
                "mStatusList=" + mStatusList +
                '}';
    }
}
