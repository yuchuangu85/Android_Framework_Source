/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.wifi.ByteBufferReader;

import java.net.ProtocolException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * The Icon Binary File vendor specific ANQP Element,
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.10.
 *
 * Format:
 *
 * | Status Code | Type Length | Type | Data Length | Data |
 *        1             1      variable      2      variable
 *
 */
public class HSIconFileElement extends ANQPElement {
    private static final String TAG = "HSIconFileElement";

    /**
     * Icon download status code.
     */
    public static final int STATUS_CODE_SUCCESS = 0;
    public static final int STATUS_CODE_FILE_NOT_FOUND = 1;
    public static final int STATUS_CODE_UNSPECIFIED_ERROR = 2;

    private final int mStatusCode;
    private final String mIconType;
    private final byte[] mIconData;

    @VisibleForTesting
    public HSIconFileElement(int statusCode, String iconType, byte[] iconData) {
        super(Constants.ANQPElementType.HSIconFile);
        mStatusCode = statusCode;
        mIconType = iconType;
        mIconData = iconData;
    }

    /**
     * Parse a HSIconFileElement from the given buffer.
     *
     * @param payload The buffer to read from
     * @return {@link HSIconFileElement}
     * @throws BufferUnderflowException
     * @throws ProtocolException
     */
    public static HSIconFileElement parse(ByteBuffer payload)
            throws ProtocolException {
        // Parse status code.
        int status = payload.get() & 0xFF;
        if (status != STATUS_CODE_SUCCESS) {
            // No more data if status code is not success.
            Log.e(TAG, "Icon file download failed: " + status);
            return new HSIconFileElement(status, null, null);
        }

        // Parse icon type.
        String iconType =
                ByteBufferReader.readStringWithByteLength(payload, StandardCharsets.US_ASCII);

        // Parse icon data.
        int iconDataLength =
                (int) ByteBufferReader.readInteger(payload, ByteOrder.LITTLE_ENDIAN, 2) & 0xFFFF;
        byte[] iconData = new byte[iconDataLength];
        payload.get(iconData);

        return new HSIconFileElement(status, iconType, iconData);
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (!(thatObject instanceof HSIconFileElement)) {
            return false;
        }
        HSIconFileElement that = (HSIconFileElement) thatObject;
        return mStatusCode == that.mStatusCode
                && TextUtils.equals(mIconType, that.mIconType)
                && Arrays.equals(mIconData, that.mIconData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStatusCode, mIconType, Arrays.hashCode(mIconData));
    }

    @Override
    public String toString() {
        return "HSIconFileElement{" + "mStatusCode=" + mStatusCode
                + "mIconType=" + mIconType + "}";
    }
}
