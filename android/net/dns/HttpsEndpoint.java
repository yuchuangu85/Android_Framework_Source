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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.DnsResolver;
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
 * Class that contains the DNS records needed to connect to an HTTPS endpoint.
 *
 * <p>This includes the data from A/AAAA and HTTPS DNS queries. The response from the HTTPS query
 * is represented as a list of {@link HttpsRecord} sorted by SvcPriority.
 *
 * <p>This class also contains a list of {@link InetAddress}, with RFC 6724 sorting. IPv4 addresses
 * are included if the {@link Network} provides IPv4 connectivity, and IPv6 addresses if it provides
 * IPv6 connectivity. This list will come from the hints in the HTTPS record and/or from the
 * responses of the A/AAAA queries.
 */
@FlaggedApi(Flags.FLAG_ENCRYPTED_CLIENT_HELLO_DNS)
public class HttpsEndpoint {

    private final List<HttpsRecord> mHttpsRecords;
    private final List<InetAddress> mIpAddresses;

    /**
     * Used by the platform to construct a {@link HttpsEndpoint}.
     *
     * <p>Note if the caller doesn't have {@code INTERNET} permission, the list of IP addresses will
     * not be sorted properly according to RFC 6724.
     *
     * @hide
     */
    public HttpsEndpoint(@NonNull Network network, @NonNull List<HttpsRecord> httpsRecords,
            @NonNull List<InetAddress> ipAddresses) {
        mHttpsRecords = httpsRecords;
        mIpAddresses = DnsUtils.rfc6724Sort(network, ipAddresses);
    }

    /**
     * Returns the list of {@link HttpsRecord} from the HTTPS DNS query, sorted by priority.
     *
     * <p>This list is empty if the HTTPS DNS query did not return any data, or if the HTTPS query
     * did not return within the specified timeout.
     */
    public @NonNull List<HttpsRecord> getHttpsRecords() {
        return Collections.unmodifiableList(mHttpsRecords);
    }

    /**
     * Returns the list of IP addresses from the A/AAAA/HTTPS queries.
     *
     * <p>If the HTTPS query returned first, this list contains the IP hints from the HTTPS record,
     * if present. Otherwise, this list contains the IP addresses returned from the A/AAAA queries
     * and the HTTPS query, only if the HTTPS query returned within the specified timeout.
     *
     * <p>This list contains only IP addresses of the address families present on the specified
     * {@link Network}, and is sorted according to RFC 6724 if the caller has {@code INTERNET}
     * permission.
     */
    public @NonNull List<InetAddress> getIpAddresses() {
        return Collections.unmodifiableList(mIpAddresses);
    }

}
