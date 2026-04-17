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

package android.net.util;

import static android.net.DnsResolver.ERROR_PARSE;
import static android.net.DnsResolver.ERROR_SYSTEM;
import static android.net.DnsResolver.TYPE_A;
import static android.net.DnsResolver.TYPE_AAAA;
import static android.net.DnsResolver.TYPE_HTTPS;

import static com.android.net.module.util.DnsPacket.ANSECTION;
import static com.android.net.module.util.DnsPacket.QDSECTION;
import static com.android.net.module.util.DnsPacket.TYPE_CNAME;
import static com.android.net.module.util.DnsPacket.TYPE_SOA;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.DnsResolver;
import android.net.DnsResolver.DnsException;
import android.net.LinkProperties;
import android.net.Network;
import android.net.ParseException;
import android.net.dns.HttpsEndpoint;
import android.net.dns.HttpsRecord;
import android.os.CancellationSignal;
import android.os.Handler;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.net.module.util.DnsHttpsRecord;
import com.android.net.module.util.DnsPacket;
import com.android.net.module.util.DnsPacket.DnsRecord;

import java.lang.ClassCastException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Accumulates the results of concurrent A/AAAA/HTTPS DNS queries.
 *
 * @hide
 */
public class HttpsEndpointAccumulator implements DnsResolver.Callback<byte[]> {

    // As specified in RFC 9460 section 5.1, clients should wait 50 ms before starting optimistic
    // pre-connection. Depending on metric results, this value may be adjusted in the future.
    private static final int DEFAULT_HTTPS_TIMEOUT_MILLIS = 50;

    private final Network mNetwork;
    private final LinkProperties mLinkProperties;
    private final DnsResolver.Callback<HttpsEndpoint> mUserCallback;
    private final int mTargetQueryCount;
    private final boolean mHasIpv4;
    private final boolean mHasIpv6;

    private final int mHttpsTimeoutMillis;
    private final Handler mHttpsTimeoutHandler;

    @GuardedBy("mResult")
    private final HttpsEndpointAccumulatorResult mResult;
    private final AtomicBoolean mCallbackInvoked = new AtomicBoolean(false);
    private final Object mTimeoutCancellationToken = new Object();
    private final CancellationSignal mUserCancellationSignal;
    private final CancellationSignal mPendingQueryCancellationSignal;

    /**
     * Nested class to group the results of the DNS queries that must be kept in sync.
     */
    private static class HttpsEndpointAccumulatorResult {
        final Set<Integer> mReceivedAnswersToQueryTypes;
        final Set<InetAddress> mAddresses;
        final List<HttpsRecord> mHttpsRecords;
        DnsException mDnsException;

        private HttpsEndpointAccumulatorResult() {
            mReceivedAnswersToQueryTypes = new HashSet<>();
            mAddresses = new LinkedHashSet<>();
            mHttpsRecords = new ArrayList<>();
        }
    }

    public HttpsEndpointAccumulator(
            @NonNull Network network,
            @Nullable LinkProperties linkProperties,
            @NonNull DnsResolver.Callback<HttpsEndpoint> callback, int queryCount,
            int timeoutMillis, boolean hasIpv4, boolean hasIpv6, @NonNull Handler handler,
            @Nullable CancellationSignal userCancellationSignal,
            @NonNull CancellationSignal pendingQueryCancellationSignal) {
        mNetwork = network;
        mLinkProperties = linkProperties;
        mUserCallback = callback;
        mTargetQueryCount = queryCount;
        mHasIpv4 = hasIpv4;
        mHasIpv6 = hasIpv6;
        mResult = new HttpsEndpointAccumulatorResult();

        mHttpsTimeoutMillis = switch(timeoutMillis) {
            case DnsResolver.HTTPS_QUERY_WAIT_NONE -> 0;
            case DnsResolver.HTTPS_QUERY_WAIT_AUTO -> DEFAULT_HTTPS_TIMEOUT_MILLIS;
            // Indicate that we should wait indefinitely for the HTTPS record.
            case DnsResolver.HTTPS_QUERY_WAIT_UNTIL_TIMEOUT -> -1;
            default -> timeoutMillis;
        };
        mHttpsTimeoutHandler = handler;
        mUserCancellationSignal = userCancellationSignal;
        mPendingQueryCancellationSignal = pendingQueryCancellationSignal;
    }

    @Override
    public void onAnswer(@NonNull byte[] answer, int rcode) {
        Objects.requireNonNull(answer, "Raw byte response for query cannot be null");

        // Callback has already been invoked, skip parsing this response.
        if (mCallbackInvoked.get()) return;
        if (mUserCancellationSignal != null && mUserCancellationSignal.isCanceled()) return;

        DnsPacket dnsPacket = new DnsPacket(answer);
        final List<DnsRecord> questionRecords = dnsPacket.getRecords(QDSECTION);
        final int questionCount = questionRecords.size();
        if (questionCount != 1) {
            synchronized (mResult) {
                mResult.mDnsException = new DnsException(ERROR_PARSE,
                        new ParseException("Unexpected question count: " + questionCount));
            }
            return;
        }

        final int queryType = questionRecords.get(0).nsType;

        if (queryType != TYPE_A && queryType != TYPE_AAAA && queryType != TYPE_HTTPS) {
            return;
        }

        final List<DnsRecord> answerRecords = dnsPacket.getRecords(ANSECTION);
        Set<InetAddress> addresses = new LinkedHashSet<>();
        List<HttpsRecord> httpsRecords = new ArrayList<>();
        DnsException exception = null;

        // In the NODATA scenario, answerRecords will be empty and this whole loop will be skipped.
        for (DnsRecord record : answerRecords) {
            int answerType = record.nsType;

            try {
                switch (answerType) {
                    case TYPE_A, TYPE_AAAA -> {
                        addresses.add(InetAddress.getByAddress(record.getRR()));
                    }
                    case TYPE_HTTPS -> {
                        DnsHttpsRecord dnsHttpsRecord = (DnsHttpsRecord) record;
                        try {
                            dnsHttpsRecord.verifyMandatoryKeys();
                        } catch (DnsPacket.ParseException e) {
                            // Ignore the HTTPS record as it is missing mandatory keys.
                            continue;
                        }

                        HttpsRecord httpsRecord =
                                new HttpsRecord(mNetwork, mLinkProperties, dnsHttpsRecord);
                        httpsRecords.add(httpsRecord);

                        // Add only the relevant IP hints to the list of IP addresses.
                        if (mHasIpv4) addresses.addAll(httpsRecord.getIpv4Hints());
                        if (mHasIpv6) addresses.addAll(httpsRecord.getIpv6Hints());
                    }
                    case TYPE_CNAME, TYPE_SOA -> {
                        // Skip CNAME and SOA records, but don't mark this as an error.
                        // Most servers will return the full CNAME chain in the answer to the final
                        // A/AAAA record. If not, we'll assume NODATA for this queryType.
                        continue;
                    }
                    default -> {
                        exception = new DnsException(ERROR_PARSE,
                                new ParseException("Unexpected answer type: " + answerType));
                    }
                }
            } catch (DnsPacket.ParseException e) {
                exception = new DnsException(ERROR_PARSE, new ParseException(e.reason, e));
            } catch (ClassCastException e) {
                exception = new DnsException(ERROR_PARSE, e);
            } catch (UnknownHostException e) {
                exception = new DnsException(ERROR_SYSTEM, e);
            }
        }

        HttpsEndpoint endpoint = null;
        DnsException exceptionToReport = null;
        boolean shouldStartHttpsTimeout = false;

        synchronized (mResult) {
            mResult.mAddresses.addAll(addresses);
            mResult.mHttpsRecords.addAll(httpsRecords);
            // Add the query type even in the case of NODATA, mismatched query/response type (even
            // in the case of CNAME responses with no actual address records), since this indicates
            // we received an answer for the type of record we were querying.
            mResult.mReceivedAnswersToQueryTypes.add(queryType);
            if (exception != null) {
                mResult.mDnsException = exception;
            }

            if (mCallbackInvoked.get()) return; // Skip if the callback has already been invoked.

            exceptionToReport = mResult.mDnsException;
            if (hasEnoughAddressInfo()) {
                if (mResult.mHttpsRecords.isEmpty()) {
                    // If we have received all but the HTTPS records, decide whether to return
                    // immediately or wait the given amount of time before returning incomplete
                    // results.
                    if (mHttpsTimeoutMillis == DnsResolver.HTTPS_QUERY_WAIT_NONE
                            || mResult.mReceivedAnswersToQueryTypes.contains(TYPE_HTTPS)) {
                        endpoint = createHttpsEndpoint(rcode);
                    } else if (mHttpsTimeoutMillis > 0) {
                        shouldStartHttpsTimeout = true;
                    }
                } else {
                    // Disregard any exceptions and return if we got valid HTTPS and IP data.
                    exceptionToReport = null;
                    endpoint = createHttpsEndpoint(rcode);
                }
            } else if (mTargetQueryCount == mResult.mReceivedAnswersToQueryTypes.size()) {
                // Return if we have gotten all the records as we expected.
                endpoint = createHttpsEndpoint(rcode);
            }
        }

        if (endpoint != null || exceptionToReport != null) {
            reportResult(endpoint, exceptionToReport, rcode);
        } else if (shouldStartHttpsTimeout) {
            mHttpsTimeoutHandler.postDelayed(() -> {
                HttpsEndpoint timeoutEndpoint = null;
                DnsException timeoutException = null;

                synchronized (mResult) {
                    timeoutEndpoint = createHttpsEndpoint(rcode);
                    timeoutException = mResult.mDnsException;
                }
                reportResult(timeoutEndpoint, timeoutException, rcode);
            }, mTimeoutCancellationToken, mHttpsTimeoutMillis);
        }
        // TODO(b/448882639): handle filtering out HTTPS records with specified mandatory values
        // that are absent
        // TODO(b/448882639): handle if the HTTPS record name does not match the A/AAAA ones
    }

    @Override
    public void onError(@NonNull DnsException error) {
        // Callback has already been invoked, skip.
        if (mCallbackInvoked.get()) return;
        if (mUserCancellationSignal != null && mUserCancellationSignal.isCanceled()) return;

        DnsException exception = null;
        synchronized (mResult) {
            mResult.mDnsException = error;
            exception = mResult.mDnsException;
        }

        reportResult(/* endpoint= */ null, exception, /* rcode= */ 0);
    }

    /**
     * Constructs the data to be returned via the user callback, whether success or failure.
     */
    @GuardedBy("mResult")
    @NonNull
    private HttpsEndpoint createHttpsEndpoint(int rcode) {
        Collections.sort(mResult.mHttpsRecords, Comparator.comparing(HttpsRecord::getPriority));
        return new HttpsEndpoint(
                mNetwork,
                mResult.mHttpsRecords,
                new ArrayList<>(mResult.mAddresses));
    }

    /**
     * Reports the DNS query results to the user callback, both success and failure.
     *
     * <p>If the user callback has already been invoked (regardless of success or failure), this
     * method does nothing.
     */
    private void reportResult(
            @Nullable HttpsEndpoint endpoint, @Nullable DnsException exception, int rcode) {
        if (!mCallbackInvoked.compareAndSet(false, true)) {
            // Callback has already been invoked, do nothing.
            return;
        }

        if (endpoint == null && exception == null) {
            // No results (success or failure) to report. This should never happen.
            return;
        }

        if (exception != null) {
            mUserCallback.onError(exception);
        } else {
            mUserCallback.onAnswer(endpoint, rcode);
        }

        // Cancel any pending queries or timeout callbacks.
        mPendingQueryCancellationSignal.cancel();
        mHttpsTimeoutHandler.removeCallbacksAndMessages(mTimeoutCancellationToken);
    }

    /**
     * Returns true if we've received enough address information to return the results we have.
     *
     * <p>This is the case if we have received at least one HTTPS record with the IP hints relevant
     * to network, or if we have received the A or AAAA records relevant to the network.
     */
    @GuardedBy("mResult")
    private boolean hasEnoughAddressInfo() {
        for (HttpsRecord record: mResult.mHttpsRecords) {
            boolean missingIpv4Hint = mHasIpv4 && record.getIpv4Hints().isEmpty();
            boolean missingIpv6Hint = mHasIpv6 && record.getIpv6Hints().isEmpty();
            if (!missingIpv4Hint && !missingIpv6Hint) {
                // If we have at least one HTTPS record with the relevant IP hints, don't bother
                // checking the other HTTPS records.
                return true;
            }
        }

        boolean hasIpv6IfNeeded = !mHasIpv6 ||
                mResult.mReceivedAnswersToQueryTypes.contains(TYPE_AAAA);
        boolean hasIpv4IfNeeded = !mHasIpv4 ||
                mResult.mReceivedAnswersToQueryTypes.contains(TYPE_A);

        return hasIpv6IfNeeded && hasIpv4IfNeeded;
    }
}
