/*
 * Copyright (C) 2023 The Android Open Source Project
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
import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;
import static com.android.net.module.util.DnsPacket.ParseException;
import static com.android.net.module.util.SvcParam.KEY_ALPN;
import static com.android.net.module.util.SvcParam.KEY_DOHPATH;
import static com.android.net.module.util.SvcParam.KEY_ECH;
import static com.android.net.module.util.SvcParam.KEY_IPV4HINT;
import static com.android.net.module.util.SvcParam.KEY_IPV6HINT;
import static com.android.net.module.util.SvcParam.KEY_MANDATORY;
import static com.android.net.module.util.SvcParam.KEY_NO_DEFAULT_ALPN;
import static com.android.net.module.util.SvcParam.KEY_PORT;
import static com.android.net.module.util.SvcParam.MINSVCPARAMSIZE;

import android.annotation.NonNull;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.SvcParam.SvcParamAlpn;
import com.android.net.module.util.SvcParam.SvcParamDohPath;
import com.android.net.module.util.SvcParam.SvcParamIpHint;
import com.android.net.module.util.SvcParam.SvcParamMandatory;
import com.android.net.module.util.SvcParam.SvcParamNoDefaultAlpn;
import com.android.net.module.util.SvcParam.SvcParamPort;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/**
 * A class for an SVCB record.
 * https://www.iana.org/assignments/dns-svcb/dns-svcb.xhtml
 * @hide
 */
@VisibleForTesting(visibility = PACKAGE)
public final class DnsSvcbRecord extends DnsPacket.DnsRecord {

    private static final String TAG = DnsSvcbRecord.class.getSimpleName();

    private final int mSvcPriority;

    @NonNull
    private final String mTargetName;

    @NonNull
    private final SparseArray<SvcParam> mAllSvcParams = new SparseArray<>();

    @VisibleForTesting(visibility = PACKAGE)
    public DnsSvcbRecord(@DnsPacket.RecordType int rType, @NonNull ByteBuffer buff)
            throws IllegalStateException, ParseException {
        super(rType, buff);
        if (nsType != DnsPacket.TYPE_SVCB) {
            throw new IllegalStateException("incorrect nsType: " + nsType);
        }
        if (nsClass != CLASS_IN) {
            throw new ParseException("incorrect nsClass: " + nsClass);
        }

        // DNS Record in Question Section doesn't have Rdata.
        if (rType == DnsPacket.QDSECTION) {
            mSvcPriority = 0;
            mTargetName = "";
            return;
        }

        final byte[] rdata = getRR();
        if (rdata == null) {
            throw new ParseException("SVCB rdata is empty");
        }

        final ByteBuffer buf = ByteBuffer.wrap(rdata).asReadOnlyBuffer();
        mSvcPriority = Short.toUnsignedInt(buf.getShort());
        mTargetName = DnsPacketUtils.DnsRecordParser.parseName(buf, 0 /* Parse depth */,
                false /* isNameCompressionSupported */);

        if (mTargetName.length() > DnsPacket.DnsRecord.MAXNAMESIZE) {
            throw new ParseException(
                    "Failed to parse SVCB target name, name size is too long: "
                            + mTargetName.length());
        }
        while (buf.remaining() >= MINSVCPARAMSIZE) {
            final SvcParam svcParam = SvcParam.parseSvcParam(buf);
            final int key = svcParam.getKey();
            if (mAllSvcParams.get(key) != null) {
                throw new ParseException("Invalid DnsSvcbRecord, key " + key + " is repeated");
            }
            mAllSvcParams.put(key, svcParam);
        }
        if (buf.hasRemaining()) {
            throw new ParseException("Invalid DnsSvcbRecord. Got "
                    + buf.remaining() + " remaining bytes after parsing");
        }
    }

    /**
     * Returns the TargetName.
     */
    @VisibleForTesting(visibility = PACKAGE)
    @NonNull
    public String getTargetName() {
        return mTargetName;
    }

    /**
     * Returns an unmodifiable list of alpns from SvcParam alpn.
     */
    @VisibleForTesting(visibility = PACKAGE)
    @NonNull
    public List<String> getAlpns() {
        final SvcParamAlpn sp = (SvcParamAlpn) mAllSvcParams.get(KEY_ALPN);
        final List<String> list = (sp != null) ? sp.getValue() : Collections.EMPTY_LIST;
        return Collections.unmodifiableList(list);
    }

    /**
     * Returns the port number from SvcParam port.
     */
    @VisibleForTesting(visibility = PACKAGE)
    public int getPort() {
        final SvcParamPort sp = (SvcParamPort) mAllSvcParams.get(KEY_PORT);
        return (sp != null) ? sp.getValue() : -1;
    }

    /**
     * Returns a list of the IP addresses from both of SvcParam ipv4hint and ipv6hint.
     */
    @VisibleForTesting(visibility = PACKAGE)
    @NonNull
    public List<InetAddress> getAddresses() {
        final List<InetAddress> out = new ArrayList<>();
        final SvcParamIpHint sp4 = (SvcParamIpHint) mAllSvcParams.get(KEY_IPV4HINT);
        if (sp4 != null) {
            out.addAll(sp4.getValue());
        }
        final SvcParamIpHint sp6 = (SvcParamIpHint) mAllSvcParams.get(KEY_IPV6HINT);
        if (sp6 != null) {
            out.addAll(sp6.getValue());
        }
        return out;
    }

    /**
     * Returns the doh path from SvcParam dohPath.
     */
    @VisibleForTesting(visibility = PACKAGE)
    @NonNull
    public String getDohPath() {
        final SvcParamDohPath sp = (SvcParamDohPath) mAllSvcParams.get(KEY_DOHPATH);
        return (sp != null) ? sp.getValue() : "";
    }

    @Override
    public String toString() {
        if (rType == DnsPacket.QDSECTION) {
            return dName + " IN SVCB";
        }

        final StringJoiner sj = new StringJoiner(" ");
        for (int i = 0; i < mAllSvcParams.size(); i++) {
            sj.add(mAllSvcParams.valueAt(i).toString());
        }
        return dName + " " + ttl + " IN SVCB " + mSvcPriority + " " + mTargetName + " "
                + sj.toString();
    }
}
