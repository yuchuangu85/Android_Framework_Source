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
 * limitations under the License.Wifi
 */

package com.android.server.wifi;

import com.android.internal.annotations.VisibleForTesting;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

/**
 * Utility class for reading generic data (e.g. various length integer, string) from ByteBuffer.
 */
public class ByteBufferReader {
    @VisibleForTesting
    public static final int MINIMUM_INTEGER_SIZE = Byte.BYTES;

    @VisibleForTesting
    public static final int MAXIMUM_INTEGER_SIZE = Long.BYTES;

    /**
     * Read an integer value from a buffer.
     *
     * @param payload The buffer to read from
     * @param byteOrder Byte order of the buffer
     * @param size The number of bytes to read from the buffer
     * @return The integer value
     * @throws BufferUnderflowException
     * @throws IllegalArgumentException
     */
    public static long readInteger(ByteBuffer payload, ByteOrder byteOrder, int size) {
        if (size < MINIMUM_INTEGER_SIZE || size > MAXIMUM_INTEGER_SIZE) {
            throw new IllegalArgumentException("Invalid size " + size);
        }

        // Read the necessary bytes.
        byte[] octets = new byte[size];
        payload.get(octets);

        // Format the value based on byte order.
        long value = 0;
        if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
            for (int n = octets.length - 1; n >= 0; n--) {
                value = (value << Byte.SIZE) | (octets[n] & 0xFF);
            }
        } else {
            for (byte octet : octets) {
                value = (value << Byte.SIZE) | (octet & 0xFF);
            }
        }
        return value;
    }

    /**
     * Read a string from a buffer. An empty String will be returned for a String with 0 length.
     *
     * @param payload The buffer to read from
     * @param length Number of bytes to read from the buffer
     * @param charset The character set of the string
     * @return {@link String}
     * @throws BufferUnderflowException
     * @throws NegativeArraySizeException
     */
    public static String readString(ByteBuffer payload, int length, Charset charset) {
        byte[] octets = new byte[length];
        payload.get(octets);
        return new String(octets, charset);
    }

    /**
     * Read a string from a buffer where the string value is preceded by the length of the string
     * (1 byte) in the buffer.
     *
     * @param payload The buffer to read from
     * @param charset The character set of the string
     * @return {@link String}
     * @throws BufferUnderflowException
     */
    public static String readStringWithByteLength(ByteBuffer payload, Charset charset) {
        int length = payload.get() & 0xFF;
        return readString(payload, length, charset);
    }
}
