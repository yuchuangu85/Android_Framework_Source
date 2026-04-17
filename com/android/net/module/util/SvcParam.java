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

import static com.android.net.module.util.DnsPacket.ParseException;
import static com.android.net.module.util.SvcParamUtils.sliceAndAdvance;

import android.annotation.NonNull;
import android.text.TextUtils;

import java.net.InetAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/**
 * The base class for all SvcParam, which consist of key-value pairs of SvcParamKey=SvcParamValue.
 *
 * <p>A SvcParam is roughly structured as follows:
 * <ul>
 *   <li>SvcParamKey (2 octets): in strict increasing numeric order
 *   <li>SvcParamValue length (2 octets)
 *   <li>SvcParamValue (variable length)
 * </ul>
 *
 * <p>See RFC 9460 for more details, especially on the format of the different SvcParamValues.
 *
 * @hide
 */
public abstract class SvcParam<T> {
    private final int mKey;

    SvcParam(int key) {
        mKey = key;
    }

    int getKey() {
        return mKey;
    }

    abstract T getValue();

    static SvcParam parseSvcParam(@NonNull ByteBuffer buf) throws ParseException {
        try {
            final int key = Short.toUnsignedInt(buf.getShort());
            return switch (key) {
                case KEY_MANDATORY -> new SvcParamMandatory(buf);
                case KEY_ALPN -> new SvcParamAlpn(buf);
                case KEY_NO_DEFAULT_ALPN -> new SvcParamNoDefaultAlpn(buf);
                case KEY_PORT -> new SvcParamPort(buf);
                case KEY_IPV4HINT -> new SvcParamIpv4Hint(buf);
                case KEY_ECH -> new SvcParamEch(buf);
                case KEY_IPV6HINT -> new SvcParamIpv6Hint(buf);
                case KEY_DOHPATH -> new SvcParamDohPath(buf);
                default -> new SvcParamGeneric(key, buf);
            };
        } catch (BufferUnderflowException e) {
            throw new ParseException("Malformed packet", e);
        }
    }

    /**
     * Representation of the `mandatory` SvcParamKey.
     *
     * <p>See RFC 9460 section 8 for more details.
     */
    static class SvcParamMandatory extends SvcParam<short[]> {
        private final short[] mValue;

        private SvcParamMandatory(@NonNull ByteBuffer buf)
                throws BufferUnderflowException, ParseException {
            super(KEY_MANDATORY);
            // The caller already read 2 bytes for SvcParamKey.
            final int len = Short.toUnsignedInt(buf.getShort());
            final ByteBuffer svcParamValue = sliceAndAdvance(buf, len);
            mValue = SvcParamUtils.toShortArray(svcParamValue);
            if (mValue.length == 0) {
                throw new ParseException("mandatory value must be non-empty");
            }
        }

        @Override
        short[] getValue() {
            return mValue;
        }

        @Override
        public String toString() {
            final StringJoiner valueJoiner = new StringJoiner(",");
            for (short key : mValue) {
                valueJoiner.add(toKeyName(key));
            }
            return toKeyName(getKey()) + "=" + valueJoiner.toString();
        }
    }

    /**
     * Representation of the `alpn` SvcParamKey.
     *
     * <p>See RFC 9460 section 7.1 for more details.
     */
    static class SvcParamAlpn extends SvcParam<List<String>> {
        private final List<String> mValue;

        SvcParamAlpn(@NonNull ByteBuffer buf) throws BufferUnderflowException, ParseException {
            super(KEY_ALPN);
            // The caller already read 2 bytes for SvcParamKey.
            final int len = Short.toUnsignedInt(buf.getShort());
            final ByteBuffer svcParamValue = sliceAndAdvance(buf, len);
            mValue = SvcParamUtils.toStringList(svcParamValue);
            if (mValue.isEmpty()) {
                throw new ParseException("alpn value must be non-empty");
            }
        }

        @Override
        List<String> getValue() {
            return Collections.unmodifiableList(mValue);
        }

        @Override
        public String toString() {
            return toKeyName(getKey()) + "=" + TextUtils.join(",", mValue);
        }
    }

    /**
     * Representation of the `no-default-alpn` SvcParamKey.
     *
     * <p>See RFC 9460 section 7.1 for more details.
     */
    static class SvcParamNoDefaultAlpn extends SvcParam<Void> {
        SvcParamNoDefaultAlpn(@NonNull ByteBuffer buf)
                throws BufferUnderflowException, ParseException {
            super(KEY_NO_DEFAULT_ALPN);
            // The caller already read 2 bytes for SvcParamKey.
            final int len = buf.getShort();
            if (len != 0) {
                throw new ParseException("no-default-alpn value must be empty");
            }
        }

        @Override
        Void getValue() {
            return null;
        }

        @Override
        public String toString() {
            return toKeyName(getKey());
        }
    }

    /**
     * Representation of the `port` SvcParamKey.
     *
     * <p>See RFC 9460 section 7.2 for more details.
     */
    static class SvcParamPort extends SvcParam<Integer> {
        private final int mValue;

        SvcParamPort(@NonNull ByteBuffer buf) throws BufferUnderflowException, ParseException {
            super(KEY_PORT);
            // The caller already read 2 bytes for SvcParamKey.
            final int len = buf.getShort();
            if (len != Short.BYTES) {
                throw new ParseException("key port len is not 2 but " + len);
            }
            mValue = Short.toUnsignedInt(buf.getShort());
        }

        @Override
        Integer getValue() {
            return mValue;
        }

        @Override
        public String toString() {
            return toKeyName(getKey()) + "=" + mValue;
        }
    }

    static class SvcParamIpHint extends SvcParam<List<InetAddress>> {
        private final List<InetAddress> mValue;

        private SvcParamIpHint(int key, @NonNull ByteBuffer buf, int addrLen)
                throws BufferUnderflowException, ParseException {
            super(key);
            // The caller already read 2 bytes for SvcParamKey.
            final int len = Short.toUnsignedInt(buf.getShort());
            final ByteBuffer svcParamValue = sliceAndAdvance(buf, len);
            mValue = SvcParamUtils.toInetAddressList(svcParamValue, addrLen);
            if (mValue.isEmpty()) {
                throw new ParseException(toKeyName(getKey()) + " value must be non-empty");
            }
        }

        @Override
        List<InetAddress> getValue() {
            return Collections.unmodifiableList(mValue);
        }

        @Override
        public String toString() {
            final StringJoiner valueJoiner = new StringJoiner(",");
            for (InetAddress ip : mValue) {
                valueJoiner.add(ip.getHostAddress());
            }
            return toKeyName(getKey()) + "=" + valueJoiner.toString();
        }
    }

    /**
     * Representation of the `ipv4hint` SvcParamKey.
     *
     * <p>See RFC 9460 section 7.3 for more details.
     */
    static class SvcParamIpv4Hint extends SvcParamIpHint {
        SvcParamIpv4Hint(@NonNull ByteBuffer buf) throws BufferUnderflowException, ParseException {
            super(KEY_IPV4HINT, buf, NetworkStackConstants.IPV4_ADDR_LEN);
        }
    }

    /**
     * Representation of the `ipv6hint` SvcParamKey.
     *
     * <p>See RFC 9460 section 7.3 for more details.
     */
    static class SvcParamIpv6Hint extends SvcParamIpHint {
        SvcParamIpv6Hint(@NonNull ByteBuffer buf) throws BufferUnderflowException, ParseException {
            super(KEY_IPV6HINT, buf, NetworkStackConstants.IPV6_ADDR_LEN);
        }
    }

    static class SvcParamEch extends SvcParamGeneric {
        SvcParamEch(@NonNull ByteBuffer buf) throws BufferUnderflowException, ParseException {
            super(KEY_ECH, buf);
        }

        @Override
        byte[] getValue() {
            // Note: this is only used for HTTPS records, and not yet exposed for SVCB
            return mValue;
        }
    }

    /**
     * Representation of the `dohpath` SvcParamKey.
     *
     * <p>See RFC 9461 section 5 for more details.
     */
    static class SvcParamDohPath extends SvcParam<String> {
        private final String mValue;

        SvcParamDohPath(@NonNull ByteBuffer buf) throws BufferUnderflowException, ParseException {
            super(KEY_DOHPATH);
            // The caller already read 2 bytes for SvcParamKey.
            final int len = Short.toUnsignedInt(buf.getShort());
            final byte[] value = new byte[len];
            buf.get(value);
            mValue = new String(value, StandardCharsets.UTF_8);
        }

        @Override
        String getValue() {
            return mValue;
        }

        @Override
        public String toString() {
            return toKeyName(getKey()) + "=" + mValue;
        }
    }

    // For other unrecognized and unimplemented SvcParams, they are stored as SvcParamGeneric.
    static class SvcParamGeneric extends SvcParam<byte[]> {
        final byte[] mValue;

        SvcParamGeneric(int key, @NonNull ByteBuffer buf)
                throws BufferUnderflowException, ParseException {
            super(key);
            // The caller already read 2 bytes for SvcParamKey.
            final int len = Short.toUnsignedInt(buf.getShort());
            mValue = new byte[len];
            buf.get(mValue);
        }

        @Override
        byte[] getValue() {
            /* Not yet implemented */
            return null;
        }

        @Override
        public String toString() {
            final StringBuilder out = new StringBuilder();
            out.append(toKeyName(getKey()));
            if (mValue != null && mValue.length > 0) {
                out.append("=");
                out.append(HexDump.toHexString(mValue));
            }
            return out.toString();
        }
    }

    /**
     * Returns the human-readable name of the SvcParamKey.
     */
    static String toKeyName(int key) {
        return switch (key) {
            case KEY_MANDATORY -> "mandatory";
            case KEY_ALPN -> "alpn";
            case KEY_NO_DEFAULT_ALPN -> "no-default-alpn";
            case KEY_PORT -> "port";
            case KEY_IPV4HINT -> "ipv4hint";
            case KEY_ECH -> "ech";
            case KEY_IPV6HINT -> "ipv6hint";
            case KEY_DOHPATH -> "dohpath";
            default -> "key" + key;
        };
    }

    /**
     * The following SvcParamKeys KEY_* are defined in
     * https://www.iana.org/assignments/dns-svcb/dns-svcb.xhtml.
     */

    /**
     * The "mandatory" key.
     *
     * <p>This key is used to indicate which other SvcParamKeys are critical (i.e. the record will
     * not function correctly if a SvcParamKey specified in this field is ignored). If a
     * client does not understand a parameter listed, the entire HTTPS record should be ignored.
     */
    static final int KEY_MANDATORY = 0;

    /**
     * The "alpn" (Application-Layer Protocol Negotiation) SvcParamKey.
     *
     * <p>Specifies a list of application-layer protocols supported by the service,
     * such as {@code "h2"} for HTTP/2 or {@code "h3"} for HTTP/3. Clients can use
     * this to select an appropriate protocol without additional round trips.
     */
    static final int KEY_ALPN = 1;

    /**
     * The "no-default-alpn" SvcParamKey.
     *
     * <p>This is a boolean flag and has no contents. Its presence indicates that clients can't
     * assume support for any ALPN protocols beyond those explicitly listed in the "alpn"
     * parameter (which must also be present in the record).
     */
    static final int KEY_NO_DEFAULT_ALPN = 2;

    /**
     * The "port" SvcParamKey.
     *
     * <p>Specifies a TCP or UDP port number for connecting to the service.
     * If absent, clients should connect to the default port for the service instead.
     */
    static final int KEY_PORT = 3;

    /**
     * The "ipv4hint" SvcParamKey.
     *
     * <p>Provides a hint of one or more IPv4 addresses for the service. Clients
     * can use these hints to optionally avoid a separate A record lookup,
     * improving connection setup time.
     */
    static final int KEY_IPV4HINT = 4;

    /**
     * The "ech" (Encrypted Client Hello) SvcParamKey.
     *
     * <p>Contains parameters necessary for clients to use Encrypted Client Hello (ECH)
     * when connecting to the service, to enhance connection privacy by encrypting the Server
     * Name Indication (SNI) extension.
     *
     * <p>This value should be passed into {@link SSLEngines#setEchConfigList()} or
     * {@link SSLSockets#setEchConfigList()} to enable ECH in a TLS handshake.
     */
    static final int KEY_ECH = 5;

    /**
     * The "ipv6hint" SvcParamKey.
     *
     * <p>Provides a hint of one or more IPv6 addresses for the service. Similar
     * to {@link #IPV4_HINT}, this can help reduce latency by optionally
     * skipping AAAA record lookups.
     */
    static final int KEY_IPV6HINT = 6;

    /**
     * The "dohpath" SvcParamKey.
     *
     * <p>Contains the path to the DoH server for the service.
     */
    static final int KEY_DOHPATH = 7;

    // The minimal size of a SvcParam.
    // https://www.ietf.org/archive/id/draft-ietf-dnsop-svcb-https-12.html#name-rdata-wire-format
    static final int MINSVCPARAMSIZE = 4;
}
