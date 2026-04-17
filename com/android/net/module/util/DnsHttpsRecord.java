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

import static android.net.DnsResolver.CLASS_IN;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;
import static com.android.net.module.util.DnsHttpsPacket.TYPE_HTTPS;
import static com.android.net.module.util.SvcParam.KEY_ALPN;
import static com.android.net.module.util.SvcParam.KEY_DOHPATH;
import static com.android.net.module.util.SvcParam.KEY_ECH;
import static com.android.net.module.util.SvcParam.KEY_IPV4HINT;
import static com.android.net.module.util.SvcParam.KEY_IPV6HINT;
import static com.android.net.module.util.SvcParam.KEY_MANDATORY;
import static com.android.net.module.util.SvcParam.KEY_NO_DEFAULT_ALPN;
import static com.android.net.module.util.SvcParam.KEY_PORT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.DnsResolver;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import com.android.net.module.util.DnsPacket.DnsRecord;
import com.android.net.module.util.DnsPacket.ParseException;
import com.android.net.module.util.DnsPacketUtils;

import com.android.net.module.util.SvcParam.SvcParamAlpn;
import com.android.net.module.util.SvcParam.SvcParamDohPath;
import com.android.net.module.util.SvcParam.SvcParamEch;
import com.android.net.module.util.SvcParam.SvcParamIpv4Hint;
import com.android.net.module.util.SvcParam.SvcParamIpv6Hint;
import com.android.net.module.util.SvcParam.SvcParamMandatory;
import com.android.net.module.util.SvcParam.SvcParamNoDefaultAlpn;
import com.android.net.module.util.SvcParam.SvcParamPort;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Class to represent the response from a HTTPS DNS query in the Answer section.
 *
 * <p>A HTTPS DNS response will be roughly structured as follows:
 * <ul>
 *   <li>Name (variable length): if name compression is used, expected value of 0xc0 0x0c.
 *   <li>Record Type (2 octets): expected value of 0x00 0x41, to correspond to TYPE_HTTPS
 *   <li>Class (2 octets): expected value of 0x00 0x01, to correspond to CLASS_IN
 *   <li>TTL (4 octets)
 *   <li>Data length (2 octets)
 *   <li>Priority (2 octets): special case if priority is 0 (AliasMode).
 *   <li>Target name (variable length): special handling if zero-length.
 *   <li>Repeated SvcParam (variable length) in strict increasing numeric order, may be empty.
 * </ul>
 *
 * <p>See RFC 9460 for more details on the expected structure and contents.
 *
 * @hide
 */
public class DnsHttpsRecord extends DnsRecord {
    private final int mSvcPriority;
    @NonNull private final String mTargetName;
    @NonNull private final SparseArray<SvcParam> mSvcParams = new SparseArray<>();

    // If no port is specified in the HTTPS record, RFC 9460 7.2 specifies to use the standard HTTPS
    // port of 443.
    @VisibleForTesting(visibility = PACKAGE)
    public static final int DEFAULT_HTTPS_PORT_VALUE = 443;
    // If no-default-alpn is absent from the HTTPS record, then RFC 9460 7.1 states the default ALPN
    // ID of "http/1.1" should be appended to the list of ALPN IDs.
    @VisibleForTesting(visibility = PACKAGE)
    public static final String DEFAULT_HTTPS_ALPN_ID = "http/1.1";

    @VisibleForTesting(visibility = PACKAGE)
    public static final int ALIAS_MODE_PRIORITY = 0;

    public DnsHttpsRecord(@DnsPacket.RecordType int rType, @NonNull ByteBuffer buff)
            throws IllegalStateException, ParseException, BufferUnderflowException {
        super(rType, buff);

        if (nsType != TYPE_HTTPS) {
            throw new IllegalStateException("incorrect nsType: " + nsType);
        }

        if (nsClass != CLASS_IN) {
            throw new ParseException("incorrect nsClass: " + nsClass);
        }

        final byte[] rdata = getRR();
        if (rdata == null || rdata.length == 0) {
            throw new ParseException("HTTPS rdata is empty");
        }

        ByteBuffer buf = ByteBuffer.wrap(rdata).asReadOnlyBuffer();
        mSvcPriority = Short.toUnsignedInt(buf.getShort());

        mTargetName = DnsPacketUtils.DnsRecordParser.parseName(buf, /* depth= */ 0,
            /* isNameCompressionSupported= */ true);

        if (mTargetName.length() > DnsPacket.DnsRecord.MAXNAMESIZE) {
            throw new ParseException(
                    "Failed to parse HTTPS target name, name size is too long: "
                            + mTargetName.length());
        }

        // According to RFC 9460 2.4.2, in AliasMode, recipients MUST ignore any SvcParams that are
        // present.
        if (mSvcPriority == ALIAS_MODE_PRIORITY) {
            // Skip processing any SvcParams if the record is in AliasMode.
            return;
        }

        // Start here as 0 is a valid SvcParamKey value
        int previousKey = -1;

        while(buf.hasRemaining()) {
            final SvcParam svcParam = SvcParam.parseSvcParam(buf);
            int key = svcParam.getKey();
            if (mSvcParams.contains(key)) {
                throw new ParseException("Invalid DnsHttpsRecord: key " + key + " is repeated");
            }

            if (key <= previousKey) {
                throw new ParseException(
                    "Invalid DnsHttpsRecord: keys must be in increasing order");
            }

            mSvcParams.put(key, svcParam);
            previousKey = key;
        }
    }

    /**
     * Verifies that all mandatory keys are present in the HTTPS record.
     *
     * @throws ParseException if a mandatory key is missing.
     */
    public void verifyMandatoryKeys() throws ParseException {
        // Check if all mandatory keys are present.
        final short[] mandatoryKeys = getMandatory();
        for (short key : mandatoryKeys) {
            final int keyInt = Short.toUnsignedInt(key);
            if (!mSvcParams.contains(keyInt)) {
                throw new ParseException("Invalid DnsHttpsRecord: mandatory key " +
                        SvcParam.toKeyName(keyInt) + " is missing");
            }
        }
    }

    /**
     * Returns the priority of the HTTPS record.
     *
     * <p>See RFC 9460 2.4 for more details.
     */
    public int getPriority() {
        return mSvcPriority;
    }

    /**
     * Returns the target name of the HTTPS record.
     *
     * <p>If the target name is `.`, RFC 9460 2.5 specifies that this indicates special rules.
     * For AliasMode RRs, this indicates that the service is not available or does not exist.
     * For ServiceMode RRs, this indicates the record's owner name is the effective target name.
     */
    public @NonNull String getTargetName() {
        return mTargetName;
    }

    /**
     * Returns the owner name of the HTTPS record.
     *
     * <p>This is the name included in the HTTPS record's DNS response, and should be used as the
     * effective target name if the HTTPS record target name is `.`.
     */
    public @NonNull String getOwnerName() {
        return dName;
    }

    /**
     * Returns the list of mandatory SvcParamKeys contained in the HTTPS record.
     *
     * <p>See RFC 9460 8 for more details.
     */
    public @NonNull short[] getMandatory() {
        final SvcParam svcParamMandatory = mSvcParams.get(KEY_MANDATORY);
        if (svcParamMandatory == null || !(svcParamMandatory instanceof SvcParamMandatory)) {
            return new short[0];
        }

        return ((SvcParamMandatory) svcParamMandatory).getValue();
    }

    /**
     * Returns the list of ALPN protocol identifiers contained in the HTTPS record.
     *
     * <p>If the record does not specify {@code no-default-alpn} SvcParamKey, the list will contain
     * the default ALPN ID "http/1.1".
     *
     * <p>See RFC 9460 7.1 for more details.
     */
    public @NonNull List<String> getAlpnIds() {
        final SvcParam svcParamAlpn = mSvcParams.get(KEY_ALPN);
        final SvcParam svcParamNoDefaultAlpn = mSvcParams.get(KEY_NO_DEFAULT_ALPN);
        boolean useDefaultAlpn = (svcParamNoDefaultAlpn == null
                || !(svcParamNoDefaultAlpn instanceof SvcParamNoDefaultAlpn));

        // If no-default-alpn is present without the alpn key, still return an empty list as this
        // means the record is not self-consistent. See RFC 9460 7.1.1-5 for more details.
        if (svcParamAlpn == null || !(svcParamAlpn instanceof SvcParamAlpn)) {
            return Collections.emptyList();
        }

        List<String> alpnIds = new ArrayList<>();
        alpnIds.addAll(((SvcParamAlpn) svcParamAlpn).getValue());
        if (useDefaultAlpn && !alpnIds.contains(DEFAULT_HTTPS_ALPN_ID)) {
            alpnIds.add(DEFAULT_HTTPS_ALPN_ID);
        }
        return Collections.unmodifiableList(alpnIds);
    }

    /**
     * Returns the port of the HTTPS record.
     *
     * <p>If the record does not specify the port SvcParamKey, the default HTTPS port value of 443
     * is returned.
     *
     * <p>See RFC 9460 7.2 for more details.
     */
    public int getPort() {
        final SvcParam svcParamPort = mSvcParams.get(KEY_PORT);
        if (svcParamPort == null || !(svcParamPort instanceof SvcParamPort)) {
            return DEFAULT_HTTPS_PORT_VALUE;
        }
        return ((SvcParamPort) svcParamPort).getValue();
    }

    /**
     * Returns the list of IPv4 hints contained in the HTTPS record.
     *
     * <p>See RFC 9460 7.3 for more details.
     */
    public @NonNull List<InetAddress> getIpv4Hints() {
        final SvcParam svcParamIpv4Hint = mSvcParams.get(KEY_IPV4HINT);
        if (svcParamIpv4Hint == null || !(svcParamIpv4Hint instanceof SvcParamIpv4Hint)) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(((SvcParamIpv4Hint) svcParamIpv4Hint).getValue());
    }

    /**
     * Returns the ECH config list contained in the HTTPS record, if present.
     *
     * <p>Due to constraints with being unable to read the Conscrypt ECH flag from the staticlibs/
     * directory, this method returns a raw byte array, which will be used to construct an
     * EchConfigList object in Connectivity/framework.
     */
    @Nullable
    public byte[] getEchConfigList() {
        // TODO(b/419020719): return an EchConfigList object directly once flags are fully rolled
        // out and cleaned up
        final SvcParamEch sp = (SvcParamEch) mSvcParams.get(KEY_ECH);
        return (sp != null) ? sp.getValue() : null;
    }

    /**
     * Returns the list of IPv6 hints contained in the HTTPS record.
     *
     * <p>See RFC 9460 7.3 for more details.
     */
    public @NonNull List<InetAddress> getIpv6Hints() {
        final SvcParam svcParamIpv6Hint = mSvcParams.get(KEY_IPV6HINT);
        if (svcParamIpv6Hint == null || !(svcParamIpv6Hint instanceof SvcParamIpv6Hint)) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(((SvcParamIpv6Hint) svcParamIpv6Hint).getValue());
    }

    /**
     * Returns the DoH path contained in the HTTPS record, if present.
     *
     * <p>See RFC 9461 5 for more details.
     */
    public @Nullable String getDohPath() {
        final SvcParam svcParamDohPath = mSvcParams.get(KEY_DOHPATH);
        if (svcParamDohPath == null || !(svcParamDohPath instanceof SvcParamDohPath)) {
            return null;
        }
        return ((SvcParamDohPath) svcParamDohPath).getValue();
    }
}
