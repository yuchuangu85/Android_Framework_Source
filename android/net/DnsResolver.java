/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net;

import static android.net.NetworkUtils.getDnsNetwork;
import static android.net.NetworkUtils.resNetworkCancel;
import static android.net.NetworkUtils.resNetworkQuery;
import static android.net.NetworkUtils.resNetworkResult;
import static android.net.NetworkUtils.resNetworkSend;
import static android.net.util.DnsUtils.haveIpv4;
import static android.net.util.DnsUtils.haveIpv6;
import static android.net.util.DnsUtils.rfc6724Sort;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_ERROR;
import static android.os.MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT;
import static android.system.OsConstants.ENONET;

import static com.android.net.module.util.DnsUtils.equalsIgnoreDnsCase;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TargetApi;
import android.content.Context;
import android.net.dns.HttpsEndpoint;
import android.net.util.HttpsEndpointAccumulator;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.system.ErrnoException;
import android.util.Log;

import com.android.net.module.util.DnsPacket;

import java.io.FileDescriptor;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Dns resolver class for asynchronous dns querying
 *
 * Note that if a client sends a query with more than 1 record in the question section but
 * the remote dns server does not support this, it may not respond at all, leading to a timeout.
 *
 */
@TargetApi(Build.VERSION_CODES.S)
public final class DnsResolver {
    private static final String TAG = "DnsResolver";
    private static final int FD_EVENTS = EVENT_INPUT | EVENT_ERROR;
    private static final int MAXPACKET = 8 * 1024;
    private static final int SLEEP_TIME_MS = 2;

    @IntDef(prefix = { "CLASS_" }, value = {
            CLASS_IN
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface QueryClass {}
    public static final int CLASS_IN = 1;

    @IntDef(prefix = { "TYPE_" },  value = {
            TYPE_A,
            TYPE_AAAA,
            TYPE_HTTPS
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface QueryType {}
    public static final int TYPE_A = 1;
    public static final int TYPE_AAAA = 28;
    @FlaggedApi(com.android.tethering.flags.Flags.FLAG_ENCRYPTED_CLIENT_HELLO_DNS)
    public static final int TYPE_HTTPS = 65;
    // TODO: add below constants as part of QueryType and the public API
    /** @hide */
    public static final int TYPE_PTR = 12;
    /** @hide */
    public static final int TYPE_TXT = 16;
    /** @hide */
    public static final int TYPE_SRV = 33;
    /** @hide */
    public static final int TYPE_ANY = 255;

    @IntDef(prefix = { "FLAG_" }, value = {
            FLAG_EMPTY,
            FLAG_NO_RETRY,
            FLAG_NO_CACHE_STORE,
            FLAG_NO_CACHE_LOOKUP
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface QueryFlag {}
    public static final int FLAG_EMPTY = 0;
    public static final int FLAG_NO_RETRY = 1 << 0;
    public static final int FLAG_NO_CACHE_STORE = 1 << 1;
    public static final int FLAG_NO_CACHE_LOOKUP = 1 << 2;

    @IntDef(prefix = { "ERROR_" }, value = {
            ERROR_PARSE,
            ERROR_SYSTEM
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface DnsError {}
    /**
     * Indicates that there was an error parsing the response the query.
     * The cause of this error is available via getCause() and is a {@link ParseException}.
     */
    public static final int ERROR_PARSE = 0;
    /**
     * Indicates that there was an error sending the query.
     * The cause of this error is available via getCause() and is an ErrnoException.
     */
    public static final int ERROR_SYSTEM = 1;

    /**
     * Indicates that no additional wait should be used for the HTTPS query.
     *
     * <p>This option means the callback will be called immediately as soon as the IP address
     * queries have received a response, regardless of whether the HTTPS query has received a
     * response or not.
     *
     * <p>This option is not recommended for security or latency because it may result
     * in HTTPS records not being returned even if they exist.
     */
    @FlaggedApi(com.android.tethering.flags.Flags.FLAG_ENCRYPTED_CLIENT_HELLO_DNS)
    public static final int HTTPS_QUERY_WAIT_NONE = 0;

    /**
     * Indicates that the default wait time should be used for the HTTPS query.
     *
     * <p>This option balances the extra latency of waiting for the HTTPS record with the latency
     * and security benefits of receiving it.
     */
    @FlaggedApi(com.android.tethering.flags.Flags.FLAG_ENCRYPTED_CLIENT_HELLO_DNS)
    public static final int HTTPS_QUERY_WAIT_AUTO = -1;

    /**
     * Indicates that the HTTPS query will be retransmitted until it gets a response, or until it
     * times out.
     *
     * <p>This option may increase latency if the HTTPS query is dropped by the network.
     */
    @FlaggedApi(com.android.tethering.flags.Flags.FLAG_ENCRYPTED_CLIENT_HELLO_DNS)
    public static final int HTTPS_QUERY_WAIT_UNTIL_TIMEOUT = -2;

    private static final int NETID_UNSET = 0;
    private static final int RCODE_NOERROR = 0;

    private static final DnsResolver sInstance = new DnsResolver();
    private final @Nullable Context mContext;
    private final @Nullable ConnectivityManager mConnectivityManager;
    private final @NonNull Looper mLooper;

    private static final String LOCALHOST = "localhost";
    private static final InetAddress LOCALHOST_V4 =
            InetAddresses.parseNumericAddress("127.0.0.1");
    private static final InetAddress LOCALHOST_V6 =
            InetAddresses.parseNumericAddress("::1");

    /**
     * Get instance for DnsResolver
     *
     * @deprecated Use {@link #DnsResolver(Context, Looper)} instead.
     */
    @FlaggedApi(com.android.tethering.flags.Flags.FLAG_ENCRYPTED_CLIENT_HELLO_DNS)
    @Deprecated
    public static @NonNull DnsResolver getInstance() {
        return sInstance;
    }

    private DnsResolver() {
        // We don't have a context, but still need to initialize the variable as there will be
        // build errors. This is only used in the legacy implementation.
        mContext = null;
        mConnectivityManager = null;
        mLooper = Looper.getMainLooper();
    }

    /**
     * Creates a {@link DnsResolver} instance.
     *
     * @param context used for internal interactions with other system services.
     * @param looper {@link Looper} for monitoring incoming replies to DNS queries. If null, then
     *     uses the value returned by {@link Looper#getMainLooper()}.
     *
     * <p>The specified {@link Looper} is not used for executing method callbacks, but if a
     * separate thread is already being used for {@link DnsResolver} interactions, it is sufficient
     * to use {@link Runnable#run()} as the {@link Executor} for {@link #query} and {@link
     * #rawQuery}.
     */
    @FlaggedApi(com.android.tethering.flags.Flags.FLAG_ENCRYPTED_CLIENT_HELLO_DNS)
    public DnsResolver(@NonNull Context context, @Nullable Looper looper) {
        Objects.requireNonNull(context, "Context cannot be null");
        mContext = context;
        mLooper = looper == null ? Looper.getMainLooper() : looper;
        mConnectivityManager = context.getSystemService(ConnectivityManager.class);
    }

    private void handleLocalhostQuery(
            boolean includeV4,
            boolean includeV6,
            @NonNull Executor executor,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull Callback<? super List<InetAddress>> callback) {
        final List<InetAddress> answers = new ArrayList<>();
        if (includeV6) {
            answers.add(LOCALHOST_V6);
        }
        if (includeV4) {
            answers.add(LOCALHOST_V4);
        }
        new Handler(mLooper).post(() -> {
            executor.execute(() -> {
                if (cancellationSignal != null && cancellationSignal.isCanceled()) return;
                callback.onAnswer(answers, RCODE_NOERROR);
            });
        });
    }

    /**
     * Base interface for answer callbacks
     *
     * @param <T> The type of the answer
     */
    public interface Callback<T> {
        /**
         * Success response to
         * {@link android.net.DnsResolver#query query()} or
         * {@link android.net.DnsResolver#rawQuery rawQuery()}.
         *
         * Invoked when the answer to a query was successfully parsed.
         *
         * @param answer <T> answer to the query.
         * @param rcode The response code in the DNS response.
         *
         * @see android.net.DnsResolver#query query()
         */
        void onAnswer(@NonNull T answer, int rcode);
        /**
         * Error response to
         * {@link android.net.DnsResolver#query query()} or
         * {@link android.net.DnsResolver#rawQuery rawQuery()}.
         *
         * Invoked when there is no valid answer to
         * {@link android.net.DnsResolver#query query()}
         * {@link android.net.DnsResolver#rawQuery rawQuery()}.
         *
         * @param error a {@link DnsException} object with additional
         *    detail regarding the failure
         */
        void onError(@NonNull DnsException error);
    }

    /**
     * Class to represent DNS error
     */
    public static class DnsException extends Exception {
       /**
        * DNS error code as one of the ERROR_* constants
        */
        @DnsError public final int code;

        public DnsException(@DnsError int code, @Nullable Throwable cause) {
            super(cause);
            this.code = code;
        }
    }

    /**
     * Send a raw DNS query.
     * The answer will be provided asynchronously through the provided {@link Callback}.
     *
     * @param network {@link Network} specifying which network to query on.
     *         {@code null} for query on default network.
     * @param query blob message to query
     * @param flags flags as a combination of the FLAGS_* constants
     * @param executor The {@link Executor} that the callback should be executed on.
     * @param cancellationSignal used by the caller to signal if the query should be
     *    cancelled. May be {@code null}.
     * @param callback a {@link Callback} which will be called to notify the caller
     *    of the result of dns query.
     */
    public void rawQuery(@Nullable Network network, @NonNull byte[] query, @QueryFlag int flags,
            @NonNull @CallbackExecutor Executor executor,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull Callback<? super byte[]> callback) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            return;
        }
        final Object lock = new Object();
        final FileDescriptor queryfd;
        try {
            queryfd = resNetworkSend((network != null)
                    ? network.getNetIdForResolv() : NETID_UNSET, query, query.length, flags);
        } catch (ErrnoException e) {
            executor.execute(() -> callback.onError(new DnsException(ERROR_SYSTEM, e)));
            return;
        }

        synchronized (lock)  {
            registerFDListener(executor, queryfd, callback, cancellationSignal, lock);
            if (cancellationSignal == null) return;
            addCancellationSignal(cancellationSignal, queryfd, lock);
        }
    }

    /**
     * Send a DNS query with the specified name, class and query type.
     * The answer will be provided asynchronously through the provided {@link Callback}.
     *
     * @param network {@link Network} specifying which network to query on.
     *         {@code null} for query on default network.
     * @param domain domain name to query
     * @param nsClass dns class as one of the CLASS_* constants
     * @param nsType dns resource record (RR) type as one of the TYPE_* constants
     * @param flags flags as a combination of the FLAGS_* constants
     * @param executor The {@link Executor} that the callback should be executed on.
     * @param cancellationSignal used by the caller to signal if the query should be
     *    cancelled. May be {@code null}.
     * @param callback a {@link Callback} which will be called to notify the caller
     *    of the result of dns query.
     */
    public void rawQuery(@Nullable Network network, @NonNull String domain,
            @QueryClass int nsClass, @QueryType int nsType, @QueryFlag int flags,
            @NonNull @CallbackExecutor Executor executor,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull Callback<? super byte[]> callback) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            return;
        }
        final Object lock = new Object();
        final FileDescriptor queryfd;
        try {
            queryfd = resNetworkQuery((network != null)
                    ? network.getNetIdForResolv() : NETID_UNSET, domain, nsClass, nsType, flags);
        } catch (ErrnoException e) {
            executor.execute(() -> callback.onError(new DnsException(ERROR_SYSTEM, e)));
            return;
        }
        synchronized (lock)  {
            registerFDListener(executor, queryfd, callback, cancellationSignal, lock);
            if (cancellationSignal == null) return;
            addCancellationSignal(cancellationSignal, queryfd, lock);
        }
    }

    private class InetAddressAnswerAccumulator implements Callback<byte[]> {
        private final List<InetAddress> mAllAnswers;
        private final Network mNetwork;
        private int mRcode;
        private DnsException mDnsException;
        private final Callback<? super List<InetAddress>> mUserCallback;
        private final int mTargetAnswerCount;
        private int mReceivedAnswerCount = 0;

        InetAddressAnswerAccumulator(@NonNull Network network, int size,
                @NonNull Callback<? super List<InetAddress>> callback) {
            mNetwork = network;
            mTargetAnswerCount = size;
            mAllAnswers = new ArrayList<>();
            mUserCallback = callback;
        }

        private boolean maybeReportError() {
            if (mRcode != 0) {
                mUserCallback.onAnswer(mAllAnswers, mRcode);
                return true;
            }
            if (mDnsException != null) {
                mUserCallback.onError(mDnsException);
                return true;
            }
            return false;
        }

        private void maybeReportAnswer() {
            if (++mReceivedAnswerCount != mTargetAnswerCount) return;
            if (mAllAnswers.isEmpty() && maybeReportError()) return;
            mUserCallback.onAnswer(rfc6724Sort(mNetwork, mAllAnswers), mRcode);
        }

        @Override
        public void onAnswer(@NonNull byte[] answer, int rcode) {
            // If at least one query succeeded, return an rcode of 0.
            // Otherwise, arbitrarily return the first rcode received.
            if (mReceivedAnswerCount == 0 || rcode == 0) {
                mRcode = rcode;
            }
            try {
                mAllAnswers.addAll(new DnsAddressAnswer(answer).getAddresses());
            } catch (DnsPacket.ParseException e) {
                // Convert the com.android.net.module.util.DnsPacket.ParseException to an
                // android.net.ParseException. This is the type that was used in Q and is implied
                // by the public documentation of ERROR_PARSE.
                //
                // DnsPacket cannot throw android.net.ParseException directly because it's @hide.
                ParseException pe = new ParseException(e.reason, e.getCause());
                pe.setStackTrace(e.getStackTrace());
                mDnsException = new DnsException(ERROR_PARSE, pe);
            }
            maybeReportAnswer();
        }

        @Override
        public void onError(@NonNull DnsException error) {
            mDnsException = error;
            maybeReportAnswer();
        }
    }

    /**
     * Send a DNS query with the specified name on a network with both IPv4 and IPv6,
     * get back a set of InetAddresses with rfc6724 sorting asynchronously.
     *
     * This method will examine the connection ability on given network, and query IPv4
     * and IPv6 if connection is available. This method will send A queries if the specified
     * {@code Network} provides IPv4 connectivity, and AAAA queries if it provides IPv6
     * connectivity.
     *
     * If at least one query succeeded with valid answer, rcode will be 0
     *
     * The answer will be provided asynchronously through the provided {@link Callback}.
     *
     * @param network {@link Network} specifying which network to query on.
     *         {@code null} for query on default network.
     * @param domain domain name to query
     * @param flags flags as a combination of the FLAGS_* constants
     * @param executor The {@link Executor} that the callback should be executed on.
     * @param cancellationSignal used by the caller to signal if the query should be
     *    cancelled. May be {@code null}.
     * @param callback a {@link Callback} which will be called to notify the
     *    caller of the result of dns query.
     */
    public void query(@Nullable Network network, @NonNull String domain, @QueryFlag int flags,
            @NonNull @CallbackExecutor Executor executor,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull Callback<? super List<InetAddress>> callback) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            return;
        }
        final Object lock = new Object();
        final Network queryNetwork;
        try {
            queryNetwork = (network != null) ? network : getDnsNetwork();
        } catch (ErrnoException e) {
            executor.execute(() -> callback.onError(new DnsException(ERROR_SYSTEM, e)));
            return;
        }

        // Follows rfc6761 to handle localhost queries.
        if (equalsIgnoreDnsCase(LOCALHOST, domain)) {
            // Only include IPv4 localhost to match the behavior of
            // InetAddress.getAllByName("localhost").
            handleLocalhostQuery(true /* includeV4 */, false /* includeV6 */, executor,
                    cancellationSignal, callback);
            return;
        }

        final boolean queryIpv6 = haveIpv6(queryNetwork);
        final boolean queryIpv4 = haveIpv4(queryNetwork);

        // This can only happen if queryIpv4 and queryIpv6 are both false.
        // This almost certainly means that queryNetwork does not exist or no longer exists.
        if (!queryIpv6 && !queryIpv4) {
            executor.execute(() -> callback.onError(
                    new DnsException(ERROR_SYSTEM, new ErrnoException("resNetworkQuery", ENONET))));
            return;
        }

        final FileDescriptor v4fd;
        final FileDescriptor v6fd;

        int queryCount = 0;

        if (queryIpv6) {
            try {
                v6fd = resNetworkQuery(queryNetwork.getNetIdForResolv(), domain, CLASS_IN,
                        TYPE_AAAA, flags);
            } catch (ErrnoException e) {
                executor.execute(() -> callback.onError(new DnsException(ERROR_SYSTEM, e)));
                return;
            }
            queryCount++;
        } else v6fd = null;

        // Avoiding gateways drop packets if queries are sent too close together
        try {
            Thread.sleep(SLEEP_TIME_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        if (queryIpv4) {
            try {
                v4fd = resNetworkQuery(queryNetwork.getNetIdForResolv(), domain, CLASS_IN, TYPE_A,
                        flags);
            } catch (ErrnoException e) {
                if (queryIpv6) resNetworkCancel(v6fd);  // Closes fd, marks it invalid.
                executor.execute(() -> callback.onError(new DnsException(ERROR_SYSTEM, e)));
                return;
            }
            queryCount++;
        } else v4fd = null;

        final InetAddressAnswerAccumulator accumulator =
                new InetAddressAnswerAccumulator(queryNetwork, queryCount, callback);

        synchronized (lock)  {
            if (queryIpv6) {
                registerFDListener(executor, v6fd, accumulator, cancellationSignal, lock);
            }
            if (queryIpv4) {
                registerFDListener(executor, v4fd, accumulator, cancellationSignal, lock);
            }
            if (cancellationSignal == null) return;
            cancellationSignal.setOnCancelListener(() -> {
                synchronized (lock)  {
                    if (queryIpv4) cancelQuery(v4fd);
                    if (queryIpv6) cancelQuery(v6fd);
                }
            });
        }
    }

    /**
     * Send a DNS query with the specified name and query type, get back a set of
     * InetAddresses with rfc6724 sorting asynchronously.
     *
     * The answer will be provided asynchronously through the provided {@link Callback}.
     *
     * @param network {@link Network} specifying which network to query on.
     *         {@code null} for query on default network.
     * @param domain domain name to query
     * @param nsType dns resource record (RR) type as one of the TYPE_* constants
     * @param flags flags as a combination of the FLAGS_* constants
     * @param executor The {@link Executor} that the callback should be executed on.
     * @param cancellationSignal used by the caller to signal if the query should be
     *    cancelled. May be {@code null}.
     * @param callback a {@link Callback} which will be called to notify the caller
     *    of the result of dns query.
     */
    public void query(@Nullable Network network, @NonNull String domain,
            @QueryType int nsType, @QueryFlag int flags,
            @NonNull @CallbackExecutor Executor executor,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull Callback<? super List<InetAddress>> callback) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            return;
        }

        // Follows rfc6761 to handle localhost queries.
        if (equalsIgnoreDnsCase(LOCALHOST, domain)) {
            // TYPE_ANY is not handled here as it is poorly defined.
            handleLocalhostQuery(nsType == TYPE_A,
                    nsType == TYPE_AAAA,
                    executor, cancellationSignal, callback);
            return;
        }

        final Object lock = new Object();
        final FileDescriptor queryfd;
        final Network queryNetwork;
        try {
            queryNetwork = (network != null) ? network : getDnsNetwork();
            queryfd = resNetworkQuery(queryNetwork.getNetIdForResolv(), domain, CLASS_IN, nsType,
                    flags);
        } catch (ErrnoException e) {
            executor.execute(() -> callback.onError(new DnsException(ERROR_SYSTEM, e)));
            return;
        }
        final InetAddressAnswerAccumulator accumulator =
                new InetAddressAnswerAccumulator(queryNetwork, 1, callback);
        synchronized (lock)  {
            registerFDListener(executor, queryfd, accumulator, cancellationSignal, lock);
            if (cancellationSignal == null) return;
            addCancellationSignal(cancellationSignal, queryfd, lock);
        }
    }

    /**
     * Send concurrent A/AAAA/HTTPS DNS queries with the specified name on a network.
     *
     * The answer to all queries will be provided asynchronously through the provided
     * {@code callback} {@link Callback}, which provides a {@link HttpsEndpoint}.
     *
     * @param network {@link Network} specifying which network to query on.
     *         {@code null} for query on default network.
     * @param domain domain name to query
     * @param flags flags as a combination of the FLAGS_* constants
     * @param executor The {@link Executor} that the callback should be executed on.
     * @param httpsTimeoutMillis the timeout in milliseconds to wait for the HTTPS query to complete
     *    after the A/AAAA queries have already completed. May be set to
     *    {@link #HTTPS_QUERY_WAIT_NONE} to disable any additional wait,
     *    {@link #HTTPS_QUERY_WAIT_AUTO} to use the default wait time,
     *    {@link #HTTPS_QUERY_WAIT_UNTIL_TIMEOUT} to wait for the HTTPS query to complete, or a
     *    specific value in milliseconds.
     * @param cancellationSignal used by the caller to signal if all the queries should be
     *    cancelled. May be {@code null}.
     * @param callback a {@link Callback} which will be called to notify the caller of the results
     *    of the A/AAAA/HTTPS DNS queries. Will return a {@link #ERROR_PARSE} if any of the DNS
     *    records cannot be parsed, and should be treated as a resolution failure.
     */
    @FlaggedApi(com.android.tethering.flags.Flags.FLAG_ENCRYPTED_CLIENT_HELLO_DNS)
    public void query(@Nullable Network network, @NonNull String domain, @QueryFlag int flags,
            @NonNull @CallbackExecutor Executor executor, int httpsTimeoutMillis,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull Callback<HttpsEndpoint> callback) {
        if (cancellationSignal != null && cancellationSignal.isCanceled()) {
            // TODO(b/448882639): add a test to check that the callback is never called even if some
            // of the answers have already come back, on cancellation signal
            return;
        }

        final Object lock = new Object();
        final Network queryNetwork;
        try {
            queryNetwork = (network != null) ? network : getDnsNetwork();
        } catch (ErrnoException e) {
            executor.execute(() -> callback.onError(new DnsException(ERROR_SYSTEM, e)));
            return;
        }

        final boolean queryIpv6 = haveIpv6(queryNetwork);
        final boolean queryIpv4 = haveIpv4(queryNetwork);

        // If queryIpv4 and queryIpv6 are both false, this almost certainly means that queryNetwork
        // does not exist or no longer exists.
        if (!queryIpv6 && !queryIpv4) {
            executor.execute(() -> callback.onError(
                    new DnsException(ERROR_SYSTEM, new ErrnoException("resNetworkQuery", ENONET))));
            return;
        }

        // Deliberately initialize all to invalid file descriptors to make cancelling simpler
        FileDescriptor httpsfd = new FileDescriptor();
        FileDescriptor v4fd = new FileDescriptor();
        FileDescriptor v6fd = new FileDescriptor();

        int netId = queryNetwork.getNetIdForResolv();
        List<FileDescriptor> allFds = new ArrayList<FileDescriptor>(3);

        try {
            httpsfd = resNetworkQuery(netId, domain, CLASS_IN, TYPE_HTTPS, flags);
            allFds.add(httpsfd);
        } catch (ErrnoException e) {
            executor.execute(() -> callback.onError(new DnsException(ERROR_SYSTEM, e)));
            return;
        }

        if (queryIpv6) {
            try {
                v6fd = resNetworkQuery(netId, domain, CLASS_IN, TYPE_AAAA, flags);
            } catch (ErrnoException e) {
                allFds.forEach(NetworkUtils::resNetworkCancel);
                executor.execute(() -> callback.onError(new DnsException(ERROR_SYSTEM, e)));
                return;
            }
            allFds.add(v6fd);
        }

        if (queryIpv4) {
            try {
                v4fd = resNetworkQuery(netId, domain, CLASS_IN, TYPE_A, flags);
            } catch (ErrnoException e) {
                allFds.forEach(NetworkUtils::resNetworkCancel);
                executor.execute(() -> callback.onError(new DnsException(ERROR_SYSTEM, e)));
                return;
            }
            allFds.add(v4fd);
        }

        final LinkProperties linkProperties = mConnectivityManager == null ? null
                : mConnectivityManager.getLinkProperties(queryNetwork);

        synchronized (lock) {
            if (cancellationSignal != null) {
                cancellationSignal.setOnCancelListener(() -> {
                    synchronized (lock) {
                        allFds.forEach(fd -> cancelQuery(fd));
                    }
                });
            }

            // If more callbacks are needed between the accumulator and DNS resolver, replace this
            // second CancellationSignal with a listener instead.
            CancellationSignal queryCancellationSignal = new CancellationSignal();
            queryCancellationSignal.setOnCancelListener(() -> {
                synchronized (lock) {
                    allFds.forEach(fd -> cancelQuery(fd));
                }
            });

            final HttpsEndpointAccumulator accumulator =
                    new HttpsEndpointAccumulator(queryNetwork, linkProperties, callback,
                            allFds.size(), httpsTimeoutMillis, queryIpv4, queryIpv6,
                            new Handler(mLooper), cancellationSignal, queryCancellationSignal);

            for (FileDescriptor fd : allFds) {
                registerFDListener(executor, fd, accumulator, cancellationSignal, lock);
            }
        }
    }

    /**
     * Class to retrieve DNS response
     *
     * @hide
     */
    public static final class DnsResponse {
        public final @NonNull byte[] answerbuf;
        public final int rcode;
        public DnsResponse(@NonNull byte[] answerbuf, int rcode) {
            this.answerbuf = answerbuf;
            this.rcode = rcode;
        }
    }

    private void registerFDListener(@NonNull Executor executor,
            @NonNull FileDescriptor queryfd, @NonNull Callback<? super byte[]> answerCallback,
            @Nullable CancellationSignal cancellationSignal, @NonNull Object lock) {
        final MessageQueue messageQueue = mLooper.getQueue();
        messageQueue.addOnFileDescriptorEventListener(
                queryfd,
                FD_EVENTS,
                (fd, events) -> {
                    // b/134310704
                    // Unregister fd event listener before resNetworkResult is called to prevent
                    // race condition caused by fd reused.
                    // For example when querying v4 and v6, it's possible that the first query ends
                    // and the fd is closed before the second request starts, which might return
                    // the same fd for the second request. By that time, the looper must have
                    // unregistered the fd, otherwise another event listener can't be registered.
                    messageQueue.removeOnFileDescriptorEventListener(fd);

                    executor.execute(() -> {
                        DnsResponse resp = null;
                        ErrnoException exception = null;
                        synchronized (lock) {
                            if (cancellationSignal != null && cancellationSignal.isCanceled()) {
                                return;
                            }
                            try {
                                resp = resNetworkResult(fd);  // Closes fd, marks it invalid.
                            } catch (ErrnoException e) {
                                Log.w(TAG, "resNetworkResult:" + e.toString());
                                exception = e;
                            }
                        }
                        if (exception != null) {
                            answerCallback.onError(new DnsException(ERROR_SYSTEM, exception));
                            return;
                        }
                        answerCallback.onAnswer(resp.answerbuf, resp.rcode);
                    });

                    // The file descriptor has already been unregistered, so it does not really
                    // matter what is returned here. In spirit 0 (meaning "unregister this FD")
                    // is still the closest to what the looper needs to do. When returning 0,
                    // Looper knows to ignore the fd if it has already been unregistered.
                    return 0;
                });
    }

    private void cancelQuery(@NonNull FileDescriptor queryfd) {
        if (!queryfd.valid()) return;
        mLooper.getQueue().removeOnFileDescriptorEventListener(queryfd);
        resNetworkCancel(queryfd);  // Closes fd, marks it invalid.
    }

    private void addCancellationSignal(@NonNull CancellationSignal cancellationSignal,
            @NonNull FileDescriptor queryfd, @NonNull Object lock) {
        cancellationSignal.setOnCancelListener(() -> {
            synchronized (lock)  {
                cancelQuery(queryfd);
            }
        });
    }

    private static class DnsAddressAnswer extends DnsPacket {
        private static final String TAG = "DnsResolver.DnsAddressAnswer";
        private static final boolean DBG = false;

        private final int mQueryType;

        DnsAddressAnswer(@NonNull byte[] data) throws ParseException {
            super(data);
            if ((mHeader.getFlags() & (1 << 15)) == 0) {
                throw new ParseException("Not an answer packet");
            }
            if (mHeader.getRecordCount(QDSECTION) == 0) {
                throw new ParseException("No question found");
            }
            // Expect only one question in question section.
            mQueryType = mRecords[QDSECTION].get(0).nsType;
        }

        public @NonNull List<InetAddress> getAddresses() {
            final List<InetAddress> results = new ArrayList<InetAddress>();
            if (mHeader.getRecordCount(ANSECTION) == 0) return results;

            for (final DnsRecord ansSec : mRecords[ANSECTION]) {
                // Only support A and AAAA, also ignore answers if query type != answer type.
                int nsType = ansSec.nsType;
                if (nsType != mQueryType || (nsType != TYPE_A && nsType != TYPE_AAAA)) {
                    continue;
                }
                try {
                    results.add(InetAddress.getByAddress(ansSec.getRR()));
                } catch (UnknownHostException e) {
                    if (DBG) {
                        Log.w(TAG, "rr to address fail");
                    }
                }
            }
            return results;
        }
    }

}
