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

package android.net.http;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.util.Pair;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Represents a proxy that can be used by {@link HttpEngine}.
 *
 * <p>How HTTP requests are sent via proxies depends on a variety of factors. For example: the type
 * of proxy being used, the HTTP version being used, whether the request is being sent to a
 * destination with an http or https URI scheme. Additionally, whether a tunnel through the proxy is
 * established, or not, also depends on these factors.
 */
@FlaggedApi(Flags.FLAG_PROXY_APIS)
public final class Proxy {

    /**
     * Schemes supported when defining a proxy.
     *
     * <p>This only affects how HttpEngine interacts with proxies, not how it connects to
     * destinations of HTTP requests. HttpEngine always negotiates an end-to-end secure connection
     * for destinations with an https URI scheme, regardless of the scheme used to identify proxies.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"SCHEME_"},
            value = {SCHEME_HTTP, SCHEME_HTTPS})
    public @interface Scheme {}

    /** Establish a plaintext connection to the proxy itself. */
    public static final int SCHEME_HTTP = 0;

    /** Establish a secure connection to the proxy itself. */
    public static final int SCHEME_HTTPS = 1;

    /**
     * Controls tunnels established via HTTP CONNECT. All methods will be invoked onto the Executor
     * specified in {@link #createHttpProxy}.
     *
     * <p>Methods within this class are invoked only when HttpEngine must establish a tunnel through
     * the proxy. Refer to {@link #createHttpProxy}'s documentation to understand when that is the
     * case.
     */
    public interface HttpConnectCallback {
        /**
         * Represents an HTTP CONNECT request being sent to the proxy server.
         *
         * <p>All methods may be called synchronously or asynchronously, on any thread.
         */
        public static final class Request implements AutoCloseable {
            /**
             * Allows the tunnel establishment request to proceed, adding the extra headers to the
             * underlying HTTP CONNECT request.
             *
             * @param extraHeaders A list of RFC 2616-compliant headers to be added to HTTP CONNECT
             *     request directed to the proxy server. This list can be empty, in which case no
             *     headers will be added. Note: these headers won't be added to the HTTP requests
             *     that will go through the tunnel once it is established.
             * @throws IllegalArgumentException If any of the headers is not RFC 2616-compliant.
             * @throws IllegalStateException If this method is called multiple times, or after
             *     {@link #close} has been called.
             */
            public void proceed(@NonNull List<Pair<String, String>> extraHeaders) {
                mBackend.proceed(Objects.requireNonNull(extraHeaders));
            }

            /**
             * Releases all resources associated with the tunnel establishment request.
             *
             * <p>If this method is called after {@link #proceed}, this will have no effect on the
             * tunnel establishment request and the underlying HTTP CONNECT, they will keep
             * proceeding.
             *
             * <p>If this method is called before {@link #proceed}, the tunnel establishment request
             * and the underlying HTTP CONNECT request will be canceled.
             *
             * <p>When a tunnel establishment request is canceled, HttpEngine will interpret it as a
             * failure to use the associated {@link Proxy}. HttpEngine will then try the next {@link
             * Proxy} in the list passed to {@link ProxyOptions} (refer to that class documentation
             * for more info).
             */
            @Override
            public void close() {
                mBackend.close();
            }

            Request(@NonNull org.chromium.net.Proxy.HttpConnectCallback.Request backend) {
                mBackend = Objects.requireNonNull(backend);
            }

            private final @NonNull org.chromium.net.Proxy.HttpConnectCallback.Request mBackend;
        }

        /**
         * Called before sending an HTTP CONNECT request to the proxy to establish a tunnel.
         *
         * <p>Allows manipulating, or canceling, said request before sending it to the proxy. Refer
         * to {@link Request} to learn how a request can be manipulated/canceled.
         *
         * @param request Represents the HTTP CONNECT request that will be sent to the proxy.
         */
        public abstract void onBeforeRequest(@NonNull Request request);

        /**
         * Called after receiving a response to the HTTP CONNECT request sent to the proxy to
         * establish a tunnel. Allows reading headers and status code.
         *
         * <p>This method must return before any HTTP request can be sent via the tunnel that is
         * being established.
         *
         * <p>This method will not be called for HTTP requests that will go through the tunnel once
         * it is established.
         *
         * <p>If this method throws any {@link java.lang.Throwable}, the fate of the tunnel will be
         * as if {@link RESPONSE_ACTION_CLOSE} had been returned. The {@link java.lang.Throwable}
         * will not be caught.
         *
         * @param responseHeaders The list of headers contained in the response to the HTTP CONNECT
         *     request.
         * @param statusCode The HTTP status code contained in the response to the HTTP CONNECT
         *     request.
         * @return A {@link OnResponseReceivedAction} value representing what should be done with
         *     this tunnel connection. Refer to {@link OnResponseReceivedAction} documentation.
         */
        public abstract @OnResponseReceivedAction int onResponseReceived(
                @NonNull List<Pair<String, String>> responseHeaders, int statusCode);

        /**
         * Actions that can be performed in response to {@link #onResponseReceived} being called.
         *
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                prefix = {"RESPONSE_ACTION_"},
                value = {RESPONSE_ACTION_CLOSE, RESPONSE_ACTION_PROCEED})
        public @interface OnResponseReceivedAction {}

        /**
         * Closes the tunnel connection, preventing it from being used to send HTTP requests.
         *
         * <p>When a tunnel connection is closed, HttpEngine will interpret it as a failure to use
         * the associated {@link Proxy}. HttpEngine will then try the next {@link Proxy} in the list
         * passed to {@link ProxyOptions} (refer to that class documentation for more info).
         */
        public static final int RESPONSE_ACTION_CLOSE = 0;

        /**
         * Proceeds establishing a tunnel.
         *
         * <p>This does not guarantee that the tunnel will successfully be established and used to
         * send HTTP requests: HttpEngine will perform additional checks prior to that. Depending on
         * their outcome, HttpEngine might still decide to close the tunnel connection. If the
         * tunnel connection ends up being closed by HttpEngine, it will be considered as a failure
         * to use the associated {@link Proxy}. HttpEngine will then try the next {@link Proxy} in
         * the list passed to {@link ProxyOptions} (refer to that class documentation for more info)
         */
        public static final int RESPONSE_ACTION_PROCEED = 1;
    }

    /**
     * Constructs an HTTP proxy.
     *
     * <p>When sending HTTP requests via an HTTP proxy, whether {@code callback} is called, or not,
     * depends on the URI scheme of the destination:
     *
     * <ul>
     *   <li>For destinations with an https URI scheme, HttpEngine establishes a tunnel through the
     *       proxy. The tunnel is established via an HTTP CONNECT request. In this case {code
     *       callback} will be called to control the HTTP CONNECT request used to establish the
     *       tunnel.
     *   <li>For destinations with an http URI scheme, HttpEngine sends an HTTP request, containing
     *       the entire URI of the destination, to the proxy. In this case {@code callback} will not
     *       be called.
     * </ul>
     *
     * @param scheme {@link Scheme} that, alongside {@code host} and {@code port}, identifies this
     *     proxy.
     * @param host Non-empty host that, alongside {@code scheme} and {@code port}, identifies this
     *     proxy.
     * @param port Port that, alongside {@code scheme} and {@code host}, identifies this proxy. Its
     *     value must be within [0, 65535].
     * @param executor Executor where {@code callback} will be invoked.
     * @param callback Callback that allows interacting with the HTTP CONNECT request, and its
     *     response, that HttpEngine sends to establish tunnels through the proxy.
     */
    public static @NonNull Proxy createHttpProxy(
            @Scheme int scheme,
            @NonNull String host,
            @IntRange(from = 0, to = 65535) int port,
            @NonNull Executor executor,
            @NonNull HttpConnectCallback callback) {
        return new Proxy(scheme, host, port, executor, callback);
    }

    /** Returns the {@link org.chromium.net.Proxy} backing this {@link android.net.http.Proxy}. */
    @NonNull
    org.chromium.net.Proxy getBackend() {
        return mProxy;
    }

    /**
     * Private to require callers to go through factory methods (e.g., {@link
     * #createHttpConnectProxy}).
     */
    private Proxy(
            @Scheme int scheme,
            @NonNull String host,
            @IntRange(from = 0, to = 65535) int port,
            @NonNull Executor executor,
            @NonNull HttpConnectCallback callback) {
        @org.chromium.net.Proxy.Scheme
        int chromiumScheme =
                switch (scheme) {
                    case SCHEME_HTTP -> org.chromium.net.Proxy.SCHEME_HTTP;
                    case SCHEME_HTTPS -> org.chromium.net.Proxy.SCHEME_HTTPS;
                    default ->
                            throw new IllegalArgumentException(
                                    String.format("Unknown scheme %d", scheme));
                };
        if (host.equals("")) {
            throw new IllegalArgumentException("host cannot be an empty string");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException(
                    String.format("port must be within [0, 65535] but it was: %d", port));
        }

        mProxy =
                org.chromium.net.Proxy.createHttpProxy(
                        chromiumScheme,
                        Objects.requireNonNull(host),
                        port,
                        Objects.requireNonNull(executor),
                        new CronetProxyHttpConnectCallback(Objects.requireNonNull(callback)));
    }

    private static final class CronetProxyHttpConnectCallback
            extends org.chromium.net.Proxy.HttpConnectCallback {
        private final @NonNull android.net.http.Proxy.HttpConnectCallback mBackend;

        CronetProxyHttpConnectCallback(
                @NonNull android.net.http.Proxy.HttpConnectCallback backend) {
            mBackend = Objects.requireNonNull(backend);
        }

        @Override
        public void onBeforeRequest(
                @NonNull org.chromium.net.Proxy.HttpConnectCallback.Request request) {
            mBackend.onBeforeRequest(
                    new android.net.http.Proxy.HttpConnectCallback.Request(
                            Objects.requireNonNull(request)));
        }

        @Override
        public @org.chromium.net.Proxy.HttpConnectCallback.OnResponseReceivedAction int
                onResponseReceived(
                        @NonNull List<Pair<String, String>> responseHeaders, int statusCode) {
            @android.net.http.Proxy.HttpConnectCallback.OnResponseReceivedAction
            int androidResult =
                    mBackend.onResponseReceived(
                            Objects.requireNonNull(responseHeaders), statusCode);
            return switch (androidResult) {
                case android.net.http.Proxy.HttpConnectCallback.RESPONSE_ACTION_PROCEED ->
                        org.chromium.net.Proxy.HttpConnectCallback.RESPONSE_ACTION_PROCEED;
                case android.net.http.Proxy.HttpConnectCallback.RESPONSE_ACTION_CLOSE ->
                        org.chromium.net.Proxy.HttpConnectCallback.RESPONSE_ACTION_CLOSE;
                default ->
                        throw new AssertionError(
                                String.format(
                                        "Unknown OnResponseReceivedAction: %d", androidResult));
            };
        }
    }

    private final @NonNull org.chromium.net.Proxy mProxy;
}
