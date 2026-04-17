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

package android.net.dns;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.DnsResolver;
import android.net.LinkProperties;
import android.net.Network;
import android.net.ParseException;
import android.net.ssl.EchConfigList;
import android.net.ssl.InvalidEchDataException;
import android.net.util.DnsUtils;

import com.android.net.module.util.DnsHttpsPacket;
import com.android.net.module.util.DnsHttpsRecord;
import com.android.net.module.util.DnsPacket;
import com.android.tethering.flags.Flags;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Class to represent a HTTPS DNS response record.
 *
 * <p>See RFC 9460 for more details on the expected structure and contents.
 */
@FlaggedApi(Flags.FLAG_ENCRYPTED_CLIENT_HELLO_DNS)
public class HttpsRecord {

    private final DnsHttpsRecord mRecord;
    private final List<InetAddress> mIpHints;

    public static final String DEFAULT_ALPN_ID = DnsHttpsRecord.DEFAULT_HTTPS_ALPN_ID;

    /**
     * Used by the platform to construct a {@link HttpsRecord}.
     *
     * @hide
     */
    public HttpsRecord(@NonNull Network network, @Nullable LinkProperties linkProperties,
            @NonNull DnsHttpsRecord httpsRecord) {
        Objects.requireNonNull(httpsRecord, "HTTPS DNS record cannot be null");

        this.mRecord = httpsRecord;

        List<InetAddress> ipHints = new ArrayList<>();
        ipHints.addAll(httpsRecord.getIpv4Hints());
        ipHints.addAll(httpsRecord.getIpv6Hints());
        this.mIpHints = DnsUtils.rfc6724Sort(linkProperties, network, ipHints);
    }

    /**
     * Returns the priority of the HTTPS record, i.e. the contents of the SvcPriority field.
     *
     * <p>If the priority is 0, the record is considered AliasMode, and will not contain any IP
     * hints, port, ALPN IDs, or ECH configurations. Otherwise, it is ServiceMode.
     *
     * <p>Because RR sets are unordered, this priority field is used to impose an ordering, where
     * smaller values are recommended for use over those with a larger priority value.
     *
     * <p>See RFC 9460 2.4 for more details.
     */
    @IntRange(from = 0, to = 65535)
    public int getPriority() {
        return mRecord.getPriority();
    }

    /**
     * Returns the target name of the HTTPS record.
     *
     * <p>If the target name is an empty string, RFC 9460 2.5 specifies that this indicates special
     * rules. For AliasMode RRs, this indicates that the service is not available or does not exist.
     * For ServiceMode RRs, this indicates the record's owner name is the effective target name.
     */
    public @NonNull String getTargetName() {
        return mRecord.getTargetName();
    }

    /**
     * Returns the owner name of the HTTPS record.
     *
     * <p>This should be used as the effective target name if the HTTPS record target name is an
     * empty string.
     */
    public @NonNull String getOwnerName() {
        return mRecord.getOwnerName();
    }

    /**
     * Returns the list of Application-Layer Protocol Negotiation (ALPN) protocol identifiers
     * contained in the HTTPS record.
     *
     * <p>If the record does not specify {@code no-default-alpn} SvcParamKey, the list will contain
     * the default ALPN ID "http/1.1".
     *
     * <p>See RFC 9460 7.1 for more details.
     */
    public @NonNull List<String> getAlpnIds() {
        return mRecord.getAlpnIds();
    }

    /**
     * Returns the port of the HTTPS record, defaulting to 443 if not specified in the record.
     *
     * <p>See RFC 9460 7.2 for more details.
     */
    public int getPort() {
        return mRecord.getPort();
    }

    /**
     * Returns the list of IPv4 IP addresses contained in the HTTPS record.
     *
     * <p>Used by the platform to make it easier to filter out IPv4 addresses, the public API
     * is instead {@link #getIpAddressHints()}.
     *
     * @hide
     */
    public @NonNull List<InetAddress> getIpv4Hints() {
        return mRecord.getIpv4Hints();
    }

    /**
     * Returns the list of IPv6 IP addresses contained in the HTTPS record.
     *
     * <p>Used by the platform to make it easier to filter out IPv6 addresses, the public API
     * is instead {@link #getIpAddressHints()}.
     *
     * @hide
     */
    public @NonNull List<InetAddress> getIpv6Hints() {
        return mRecord.getIpv6Hints();
    }

    /**
     * Returns the list of IP hints (IPv4 & IPv6) contained in the HTTPS record.
     *
     * <p>This method returns all the hints present in the record, including address families that
     * are not present on the current network. To determine which IP addresses to use when
     * establishing a connection, use {@link HttpsEndpoint#getIpAddresses()} instead.
     *
     * <p>The list is sorted according to RFC 6724. See RFC 9460 7.3 for more details.
     */
    public @NonNull List<InetAddress> getIpAddressHints() {
        return Collections.unmodifiableList(mIpHints);
    }

    /**
     * Returns the {@link EchConfigList} contained in the HTTPS record, if present.
     *
     * <p>This value should be passed into {@link SSLEngines#setEchConfigList()} or
     * {@link SSLSockets#setEchConfigList()} to enable ECH in a TLS handshake.
     */
    @FlaggedApi(com.android.org.conscrypt.net.flags.Flags.FLAG_ENCRYPTED_CLIENT_HELLO_PLATFORM)
    public @Nullable EchConfigList getEchConfigList() throws InvalidEchDataException {
        return EchConfigList.fromBytes(mRecord.getEchConfigList());
    }

}
