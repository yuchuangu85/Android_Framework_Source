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

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;
import static com.android.net.module.util.DnsPacket.ParseException;

import android.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A utility to convert the byte array of SvcParamValue to other types.
 *
 * @hide
 */
public class SvcParamUtils {

    /**
     * Returns a read-only ByteBuffer (with position = 0, limit = `length`, and capacity = `length`)
     * sliced from `buf`'s current position, and moves the position of `buf` by `length`.
     */
    @VisibleForTesting(visibility = PRIVATE)
    public static ByteBuffer sliceAndAdvance(@NonNull ByteBuffer buf, int length)
            throws BufferUnderflowException {
        if (buf.remaining() < length) {
            throw new BufferUnderflowException();
        }
        final int pos = buf.position();

        // `out` equals to `buf.slice(pos, length)` that is supported in API level 34.
        final ByteBuffer out = ((ByteBuffer) buf.slice().limit(length)).slice();

        buf.position(pos + length);
        return out.asReadOnlyBuffer();
    }

    // Refer to draft-ietf-dnsop-svcb-https-10#section-7.1 for the wire format of alpn.
    @NonNull
    static List<String> toStringList(@NonNull ByteBuffer buf)
            throws BufferUnderflowException, ParseException {
        final List<String> out = new ArrayList<>();
        while (buf.hasRemaining()) {
            final int alpnLen = Byte.toUnsignedInt(buf.get());
            if (alpnLen == 0) {
                throw new ParseException("alpn should not be an empty string");
            }
            final byte[] alpn = new byte[alpnLen];
            buf.get(alpn);
            out.add(new String(alpn, StandardCharsets.UTF_8));
        }
        return out;
    }

    // Refer to draft-ietf-dnsop-svcb-https-10#section-7.5 for the wire format of SvcParamKey
    // "mandatory".
    @NonNull
    static short[] toShortArray(@NonNull ByteBuffer buf)
            throws BufferUnderflowException, ParseException {
        if (buf.remaining() % Short.BYTES != 0) {
            throw new ParseException("Can't parse whole byte array");
        }
        final ShortBuffer sb = buf.asShortBuffer();
        final short[] out = new short[sb.remaining()];
        sb.get(out);
        return out;
    }

    // Refer to draft-ietf-dnsop-svcb-https-10#section-7.4 for the wire format of ipv4hint and
    // ipv6hint.
    @NonNull
    static List<InetAddress> toInetAddressList(@NonNull ByteBuffer buf, int addrLen)
            throws BufferUnderflowException, ParseException {
        if (buf.remaining() % addrLen != 0) {
            throw new ParseException("Can't parse whole byte array");
        }

        final List<InetAddress> out = new ArrayList<>();
        final byte[] addr = new byte[addrLen];
        while (buf.remaining() >= addrLen) {
            buf.get(addr);
            try {
                out.add(InetAddress.getByAddress(addr));
            } catch (UnknownHostException e) {
                throw new ParseException("Can't parse byte array as an IP address");
            }
        }
        return out;
    }
}