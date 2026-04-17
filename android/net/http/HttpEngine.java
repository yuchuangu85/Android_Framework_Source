// Copyright 2015 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package android.net.http;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;
import static android.net.http.ConnectionMigrationOptions.MIGRATION_OPTION_ENABLED;
import static android.net.http.ConnectionMigrationOptions.MIGRATION_OPTION_UNSPECIFIED;
import static android.net.http.DnsOptions.DNS_OPTION_ENABLED;
import static android.net.http.DnsOptions.DNS_OPTION_UNSPECIFIED;
import static android.net.http.HttpEngineJavaClasses.ALL_CLASSES;

import android.annotation.FlaggedApi;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.content.Context;
import android.net.Network;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.chromium.base.metrics.ScopedSysTraceEvent;
import org.chromium.net.ApiVersion;
import org.chromium.net.ExperimentalCronetEngine;
import org.chromium.net.impl.CronetLibraryLoader;
import org.chromium.net.impl.NativeCronetEngineBuilderImpl;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandlerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.net.ssl.HttpsURLConnection;

/**
 * An engine to process {@link UrlRequest}s, which uses the best HTTP stack available on the current
 * platform. An instance of this class can be created using {@link Builder}.
 */
// SuppressLint: Making the HttpEngine AutoCloseable indicates to the developers that it's
// expected to be used in a try-with-resource clause. This in turn promotes local, narrowly
// scoped instances of HttpEngine. That's the exact opposite of how HttpEngine is supposed
// to be used - it should live in an application-wide scope and be reused multiple times across
// the lifespan of the app.
@SuppressLint("NotCloseable")
public abstract class HttpEngine {
    private static String TAG = HttpEngine.class.getSimpleName();
    private static boolean sPreloaded = false;

    /** @hide */
    protected HttpEngine() {}

    /**
     * Calling this will preload HttpEngine's Impl code.
     * This is mostly meant to be called from the Zygote during init to reduce
     * the impact of loading HttpEngine during app's startup.
     *
     * @hide
     */
    @SystemApi(client=MODULE_LIBRARIES)
    @FlaggedApi(Flags.FLAG_PRELOAD_HTTPENGINE_IN_ZYGOTE)
    public static void preload() {
        if (sPreloaded) {
            throw new IllegalStateException("HttpEngine already preloaded");
        }

        CronetLibraryLoader.preload();
        final HashSet<String> essentialClasses =
                new HashSet<>(
                        Arrays.asList(
                                "android.net.http.internal.org.chromium.net.impl.CronetUrlRequestContext",
                                "android.net.http.internal.org.chromium.net.impl.CronetUrlRequest",
                                "android.net.http.internal.org.chromium.net.CronetEngine",
                                "android.net.http.CronetEngineWrapper"));

        for (String clazz : ALL_CLASSES) {
            try {
                // Load and explicitly initialize the given class. Use
                // Class.forName(String, boolean, ClassLoader) to avoid repeated stack lookups
                // (to derive the caller's class-loader). Use true to force initialization, and
                // null for the boot classpath class-loader (could as well cache the
                // class-loader of this class in a variable).
                Class.forName(clazz, true, null);
            } catch (ClassNotFoundException e) {
                // R8 can strip out unused classes so it's completely normal to see
                // classNotFound exception as we generate the list of classes before stripping
                // not after. Generating the list after stripping is extremely hard as R8
                // runs at the very end of the compilation process, which means that any
                // java code must be present before R8 kicks in which creates a cycle.
                if (essentialClasses.contains(clazz)) {
                    throw new IllegalStateException(
                            String.format(
                                    "Essential class '%s' not found. This could indicate a "
                                            + "problem with optimizer stripping important classes.",
                                    clazz),
                            e);
                }
            }
        }
        sPreloaded = true;
    }

    /**
     * Returns a new {@link Builder} object that facilitates creating a {@link HttpEngine}.
     *
     * @hide
     */
    @NonNull
    public static Builder builder(@NonNull Context context) {
        return new Builder(context);
    }

    /**
     * A builder for {@link HttpEngine}s, which allows runtime configuration of
     * {@link HttpEngine}. Configuration options are set on the builder and
     * then {@link #build} is called to create the {@link HttpEngine}.
     */
    // NOTE(kapishnikov): In order to avoid breaking the existing API clients, all future methods
    // added to this class and other API classes must have default implementation.
    // SuppressLint: Builder can not be final since ExperimentalHttpEngine.Builder inherit this
    // Builder.
    @SuppressLint("StaticFinalBuilder")
    public static class Builder {

        /**
         * Constructs a {@link Builder} object that facilitates creating a
         * {@link HttpEngine}. The default configuration enables HTTP/2 and
         * QUIC, but disables the HTTP cache.
         *
         * @param context Android {@link Context}, which is used by {@link Builder} to retrieve the
         * application context. A reference to only the application context will be kept, so as to
         * avoid extending the lifetime of {@code context} unnecessarily.
         */
        public Builder(@NonNull Context context) {
            try (var traceEvent = ScopedSysTraceEvent.scoped("HttpEngine.Builder#init")) {
                mBackend =
                        new ExperimentalCronetEngine.Builder(
                                new NativeCronetEngineBuilderImpl(context));
            }
        }

        /**
         * Constructs a default User-Agent string including the system build version, model and id,
         * and the HTTP stack version.
         *
         * @return User-Agent string.
         */
        // SuppressLint: API to get default user agent that could include system build version,
        // model, Id, and Cronet version.
        @NonNull @SuppressLint("GetterOnBuilder")
        public String getDefaultUserAgent() {
            return mBackend.getDefaultUserAgent();
        }

        /**
         * Overrides the User-Agent header for all requests. An explicitly set User-Agent header
         * (set using {@link UrlRequest.Builder#addHeader}) will override a value set using this
         * function.
         *
         * @param userAgent the User-Agent string to use for all requests.
         * @return the builder to facilitate chaining.
         */
        // SuppressLint: Value is passed to JNI code and maintained by JNI code after build
        @NonNull @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setUserAgent(@NonNull String userAgent) {
            mBackend.setUserAgent(userAgent);
            return this;
        }

        /**
         * Sets directory for HTTP Cache and Cookie Storage. The directory must
         * exist.
         * <p>
         * <b>NOTE:</b> Do not use the same storage directory with more than one
         * {@link HttpEngine} at a time. Access to the storage directory does
         * not support concurrent access by multiple {@link HttpEngine} instances.
         *
         * @param value path to existing directory.
         * @return the builder to facilitate chaining.
         */
        // SuppressLint: Value is passed to JNI code and maintained by JNI code after build
        @NonNull @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setStoragePath(@NonNull String value) {
            mBackend.setStoragePath(value);
            return this;
        }

        /**
         * Sets whether <a href="https://www.chromium.org/quic">QUIC</a> protocol
         * is enabled. Defaults to enabled.
         *
         * @param value {@code true} to enable QUIC, {@code false} to disable.
         * @return the builder to facilitate chaining.
         */
        // SuppressLint: Value is passed to JNI code and maintained by JNI code after build
        @NonNull @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setEnableQuic(boolean value) {
            mBackend.enableQuic(value);
            return this;
        }

        /**
         * Sets whether <a href="https://tools.ietf.org/html/rfc7540">HTTP/2</a> protocol is
         * enabled. Defaults to enabled.
         *
         * @param value {@code true} to enable HTTP/2, {@code false} to disable.
         * @return the builder to facilitate chaining.
         */
        // SuppressLint: Value is passed to JNI code and maintained by JNI code after build
        @NonNull @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setEnableHttp2(boolean value) {
            mBackend.enableHttp2(value);
            return this;
        }

        /**
         * Sets whether <a href="https://tools.ietf.org/html/rfc7932">Brotli</a> compression is
         * enabled. If enabled, Brotli will be advertised in Accept-Encoding request headers.
         * Defaults to disabled.
         *
         * @param value {@code true} to enable Brotli, {@code false} to disable.
         * @return the builder to facilitate chaining.
         */
        // SuppressLint: Value is passed to JNI code and maintained by JNI code after build
        @NonNull @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setEnableBrotli(boolean value) {
            mBackend.enableBrotli(value);
            return this;
        }

        /**
         * Setting to disable HTTP cache. Some data may still be temporarily stored in memory.
         * Passed to {@link #setEnableHttpCache}.
         */
        public static final int HTTP_CACHE_DISABLED = 0;

        /**
         * Setting to enable in-memory HTTP cache, including HTTP data.
         * Passed to {@link #setEnableHttpCache}.
         */
        public static final int HTTP_CACHE_IN_MEMORY = 1;

        /**
         * Setting to enable on-disk cache, excluding HTTP data.
         * {@link #setStoragePath} must be called prior to passing this constant to
         * {@link #setEnableHttpCache}.
         */
        public static final int HTTP_CACHE_DISK_NO_HTTP = 2;

        /**
         * Setting to enable on-disk cache, including HTTP data.
         * {@link #setStoragePath} must be called prior to passing this constant to
         * {@link #setEnableHttpCache}.
         */
        public static final int HTTP_CACHE_DISK = 3;

        /**
         * Enables or disables caching of HTTP data and other information like QUIC server
         * information.
         *
         * @param cacheMode control location and type of cached data. Must be one of {@link
         * #HTTP_CACHE_DISABLED HTTP_CACHE_*}.
         * @param maxSize maximum size in bytes used to cache data (advisory and maybe exceeded at
         * times).
         * @return the builder to facilitate chaining.
         */
        // SuppressLint: Value is passed to JNI code and maintained by JNI code after build
        @NonNull @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setEnableHttpCache(int cacheMode, long maxSize) {
            mBackend.enableHttpCache(cacheMode, maxSize);
            return this;
        }

        /**
         * Adds hint that {@code host} supports QUIC.
         * Note that {@link #setEnableHttpCache enableHttpCache}
         * ({@link #HTTP_CACHE_DISK}) is needed to take advantage of 0-RTT
         * connection establishment between sessions.
         *
         * @param host hostname of the server that supports QUIC.
         * @param port host of the server that supports QUIC.
         * @param alternatePort alternate port to use for QUIC.
         * @return the builder to facilitate chaining.
         */
        // SuppressLint: Value is passed to JNI code and maintained by JNI code after build
        @NonNull @SuppressLint("MissingGetterMatchingBuilder")
        public Builder addQuicHint(@NonNull String host, int port, int alternatePort) {
            mBackend.addQuicHint(host, port, alternatePort);
            return this;
        }

        /**
         * Pins a set of public keys for a given host. By pinning a set of public keys, {@code
         * pinsSha256}, communication with {@code hostName} is required to authenticate with a
         * certificate with a public key from the set of pinned ones. An app can pin the public key
         * of the root certificate, any of the intermediate certificates or the end-entry
         * certificate. Authentication will fail and secure communication will not be established if
         * none of the public keys is present in the host's certificate chain, even if the host
         * attempts to authenticate with a certificate allowed by the device's trusted store of
         * certificates.
         *
         * <p>Calling this method multiple times with the same host name overrides the previously
         * set pins for the host.
         *
         * <p>More information about the public key pinning can be found in <a
         * href="https://tools.ietf.org/html/rfc7469">RFC 7469</a>.
         *
         * @param hostName name of the host to which the public keys should be pinned. A host that
         * consists only of digits and the dot character is treated as invalid.
         * @param pinsSha256 a set of pins. Each pin is the SHA-256 cryptographic hash of the
         * DER-encoded ASN.1 representation of the Subject Public Key Info (SPKI) of the host's
         * X.509 certificate. Use {@link java.security.cert.Certificate#getPublicKey()
         * Certificate.getPublicKey()} and {@link java.security.Key#getEncoded() Key.getEncoded()}
         * to obtain DER-encoded ASN.1 representation of the SPKI. Although, the method does not
         * mandate the presence of the backup pin that can be used if the control of the primary
         * private key has been lost, it is highly recommended to supply one.
         * @param includeSubdomains indicates whether the pinning policy should be applied to
         *                          subdomains of {@code hostName}.
         * @param expirationInstant specifies the expiration instant for the pins.
         * @return the builder to facilitate chaining.
         * @throws NullPointerException if any of the input parameters are {@code null}.
         * @throws IllegalArgumentException if the given host name is invalid or {@code pinsSha256}
         * contains a byte array that does not represent a valid SHA-256 hash.
         */
        // SuppressLint: Value is passed to JNI code and maintained by JNI code after build
        @NonNull @SuppressLint("MissingGetterMatchingBuilder")
        public Builder addPublicKeyPins(@NonNull String hostName, @NonNull Set<byte[]> pinsSha256,
                boolean includeSubdomains, @NonNull Instant expirationInstant) {
            mBackend.addPublicKeyPins(
                    hostName, pinsSha256, includeSubdomains, Date.from(expirationInstant));
            return this;
        }

        /**
         * Enables or disables public key pinning bypass for local trust anchors. Disabling the
         * bypass for local trust anchors is highly discouraged since it may prohibit the app from
         * communicating with the pinned hosts. E.g., a user may want to send all traffic through an
         * SSL enabled proxy by changing the device proxy settings and adding the proxy certificate
         * to the list of local trust anchor. Disabling the bypass will most likely prevent the app
         * from sending any traffic to the pinned hosts. For more information see 'How does key
         * pinning interact with local proxies and filters?' at
         * https://www.chromium.org/Home/chromium-security/security-faq
         *
         * @param value {@code true} to enable the bypass, {@code false} to disable.
         * @return the builder to facilitate chaining.
         */
        // SuppressLint: Value is passed to JNI code and maintained by JNI code after build
        @NonNull @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setEnablePublicKeyPinningBypassForLocalTrustAnchors(boolean value) {
            mBackend.enablePublicKeyPinningBypassForLocalTrustAnchors(value);
            return this;
        }

        /**
         * Configures the behavior of the HTTP stack when using QUIC. For more details, see
         * documentation of {@link QuicOptions} and the individual methods of {@link
         * QuicOptions.Builder}.
         *
         * <p>Only relevant if {@link #setEnableQuic(boolean)} is enabled.
         *
         * @return the builder to facilitate chaining.
         */
        // SuppressLint: Value is passed to JNI code and maintained by JNI code after build
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        @QuicOptions.Experimental
        public Builder setQuicOptions(@NonNull QuicOptions options) {
            mExperimentalOptionsPatches.add(
                    (experimentalOptions) -> {
                        JSONObject quicOptions = createDefaultIfAbsent(experimentalOptions, "QUIC");

                        if (!options.getAllowedQuicHosts().isEmpty()) {
                            quicOptions.put(
                                    "host_whitelist",
                                    String.join(",", options.getAllowedQuicHosts()));
                        }

                        if (options.hasInMemoryServerConfigsCacheSize()) {
                            quicOptions.put(
                                    "max_server_configs_stored_in_properties",
                                    options.getInMemoryServerConfigsCacheSize());
                        }

                        if (options.getHandshakeUserAgent() != null) {
                            quicOptions.put("user_agent_id", options.getHandshakeUserAgent());
                        }

                        if (options.getIdleConnectionTimeout() != null) {
                            quicOptions.put(
                                    "idle_connection_timeout_seconds",
                                    options.getIdleConnectionTimeout().toSeconds());
                        }
                    });
            return this;
        }

        /**
         * Configures the behavior of hostname lookup. For more details, see documentation of {@link
         * DnsOptions} and the individual methods of {@link DnsOptions.Builder}.
         *
         * <p>Only relevant if {@link #setEnableQuic(boolean)} is enabled.
         *
         * @return the builder to facilitate chaining.
         */
        // SuppressLint: Value is passed to JNI code and maintained by JNI code after build
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        @DnsOptions.Experimental
        public Builder setDnsOptions(@NonNull DnsOptions options) {
            mExperimentalOptionsPatches.add(
                    (experimentalOptions) -> {
                        JSONObject asyncDnsOptions =
                                createDefaultIfAbsent(experimentalOptions, "AsyncDNS");

                        if (options.getUseHttpStackDnsResolver() != DNS_OPTION_UNSPECIFIED) {
                            asyncDnsOptions.put(
                                    "enable",
                                    options.getUseHttpStackDnsResolver() == DNS_OPTION_ENABLED);
                        }

                        JSONObject staleDnsOptions =
                                createDefaultIfAbsent(experimentalOptions, "StaleDNS");

                        if (options.getStaleDns() != DNS_OPTION_UNSPECIFIED) {
                            staleDnsOptions.put(
                                    "enable", options.getStaleDns() == DNS_OPTION_ENABLED);
                        }

                        if (options.getPersistHostCache() != DNS_OPTION_UNSPECIFIED) {
                            staleDnsOptions.put(
                                    "persist_to_disk",
                                    options.getPersistHostCache() == DNS_OPTION_ENABLED);
                        }

                        if (options.getPersistHostCachePeriod() != null) {
                            staleDnsOptions.put(
                                    "persist_delay_ms",
                                    options.getPersistHostCachePeriod().toMillis());
                        }

                        if (options.getStaleDnsOptions() != null) {
                            android.net.http.DnsOptions.StaleDnsOptions staleDnsOptionsJava =
                                    options.getStaleDnsOptions();

                            if (staleDnsOptionsJava.getAllowCrossNetworkUsage()
                                    != DNS_OPTION_UNSPECIFIED) {
                                staleDnsOptions.put(
                                        "allow_other_network",
                                        staleDnsOptionsJava.getAllowCrossNetworkUsage()
                                                == DNS_OPTION_ENABLED);
                            }

                            if (staleDnsOptionsJava.getFreshLookupTimeout() != null) {
                                staleDnsOptions.put(
                                        "delay_ms",
                                        staleDnsOptionsJava.getFreshLookupTimeout().toMillis());
                            }

                            if (staleDnsOptionsJava.getUseStaleOnNameNotResolved()
                                    != DNS_OPTION_UNSPECIFIED) {
                                staleDnsOptions.put(
                                        "use_stale_on_name_not_resolved",
                                        staleDnsOptionsJava.getUseStaleOnNameNotResolved()
                                                == DNS_OPTION_ENABLED);
                            }

                            if (staleDnsOptionsJava.getMaxExpiredDelay() != null) {
                                staleDnsOptions.put(
                                        "max_expired_time_ms",
                                        staleDnsOptionsJava.getMaxExpiredDelay().toMillis());
                            }
                        }

                        JSONObject quicOptions = createDefaultIfAbsent(experimentalOptions, "QUIC");

                        if (options.getPreestablishConnectionsToStaleDnsResults()
                                != DNS_OPTION_UNSPECIFIED) {
                            quicOptions.put(
                                    "race_stale_dns_on_connection",
                                    options.getPreestablishConnectionsToStaleDnsResults()
                                            == DNS_OPTION_ENABLED);
                        }
                    });
            return this;
        }

        /**
         * Configures the behavior of connection migration. For more details, see documentation of
         * {@link ConnectionMigrationOptions} and the individual methods of {@link
         * ConnectionMigrationOptions.Builder}.
         *
         * <p>Only relevant if {@link #setEnableQuic(boolean)} is enabled.
         *
         * @return the builder to facilitate chaining.
         */
        // SuppressLint: Value is passed to JNI code and maintained by JNI code after build
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        @ConnectionMigrationOptions.Experimental
        public Builder setConnectionMigrationOptions(@NonNull ConnectionMigrationOptions options) {
            // If not, we'll have to work around it by modifying the experimental options JSON.
            mExperimentalOptionsPatches.add(
                    (experimentalOptions) -> {
                        JSONObject quicOptions = createDefaultIfAbsent(experimentalOptions, "QUIC");

                        if (options.getDefaultNetworkMigration() != MIGRATION_OPTION_UNSPECIFIED) {
                            quicOptions.put(
                                    "migrate_sessions_on_network_change_v2",
                                    options.getDefaultNetworkMigration()
                                            == MIGRATION_OPTION_ENABLED);
                        }

                        if (options.getPathDegradationMigration() != MIGRATION_OPTION_UNSPECIFIED) {
                            boolean pathDegradationValue =
                                    options.getPathDegradationMigration()
                                            == MIGRATION_OPTION_ENABLED;
                            boolean skipPortMigrationFlag = false;

                            if (options.getAllowNonDefaultNetworkUsage()
                                    != MIGRATION_OPTION_UNSPECIFIED) {
                                boolean nonDefaultNetworkValue =
                                        options.getAllowNonDefaultNetworkUsage()
                                                == MIGRATION_OPTION_ENABLED;
                                if (!pathDegradationValue && nonDefaultNetworkValue) {
                                    // Misconfiguration which doesn't translate easily to the JSON
                                    // flags
                                    throw new IllegalArgumentException(
                                            "Unable to turn on non-default network usage without"
                                                    + " path degradation migration!");
                                } else if (pathDegradationValue && nonDefaultNetworkValue) {
                                    // Both values being true results in the non-default network
                                    // migration
                                    // being enabled.
                                    quicOptions.put("migrate_sessions_early_v2", true);
                                    skipPortMigrationFlag = true;
                                } else {
                                    quicOptions.put("migrate_sessions_early_v2", false);
                                }
                            }

                            if (!skipPortMigrationFlag) {
                                quicOptions.put("allow_port_migration", pathDegradationValue);
                            }
                        }
                    });
            return this;
        }

        /**
         * Configures proxying behavior. This affects, in different ways: connections establishment,
         * {@link UrlRequest} and {@link BidirectionalStream}. For more details, refer to the
         * documentation of {@link Proxy}.
         *
         * <p>This is not to be confused with proxy configuration that have been set up by: the
         * user; or some enterprise profile configuration, or (most likely) some network
         * autoconfiguration (e.g., Web Proxy Auto-Discovery Protocol). This is usually referred to
         * as "system" proxy configuration. If present, respecting the system proxy configuration is
         * often a requirement to obtain local and/or internet connectivity. HttpEngine already
         * handles the system proxy configuration internally.
         *
         * <p>A proxy configuration defined via this API are refererred to as "app" proxy
         * configuration. App and system proxy configuration are separate and, most importantly,
         * differ. Currently, app and system proxy configurations are mutually exclusive: specifying
         * {@link ProxyOptions} overrides the system proxy configuration, if present. This might
         * cause connectivity problems in some scenarios where a system proxy configuration is
         * present. In such scenarios, users might end up with no internet access, unless {@link
         * ProxyOptions} has been configured with a final, {@code null}, fallback. Refer to {@link
         * ProxyOptions} documentation.
         *
         * @param options ProxyOptions to be used for {@link UrlRequest}, {@link
         *     BiridirectionalStream} and connections established by the {@link HttpEngine} created
         *     by this builder.
         * @return the builder to facilitate chaining.
         */
        @FlaggedApi(Flags.FLAG_PROXY_APIS)
        // SuppressLint: Value is passed to JNI code and maintained by JNI code after build.
        @SuppressLint("MissingGetterMatchingBuilder")
        public @NonNull Builder setProxyOptions(@NonNull ProxyOptions options) {
            ProxyOptions.apply(mBackend, Objects.requireNonNull(options));
            return this;
        }

        /**
         * Build a {@link HttpEngine} using this builder's configuration.
         *
         * @return constructed {@link HttpEngine}.
         */
        @NonNull
        public HttpEngine build() {
            try (var traceEvent = ScopedSysTraceEvent.scoped("HttpEngine#build")) {
                for (ExperimentalOptionsPatch patch : mExperimentalOptionsPatches) {
                    try {
                        patch.applyTo(mParsedExperimentalOptions);
                    } catch (JSONException e) {
                        throw new IllegalStateException("Unable to apply JSON patch!", e);
                    }
                }
                mBackend.setExperimentalOptions(mParsedExperimentalOptions.toString());
                return new CronetEngineWrapper(mBackend.build());
            }
        }

        private static JSONObject createDefaultIfAbsent(JSONObject jsonObject, String key) {
            JSONObject object = jsonObject.optJSONObject(key);
            if (object == null) {
                object = new JSONObject();
                try {
                    jsonObject.put(key, object);
                } catch (JSONException e) {
                    throw new IllegalArgumentException(
                            "Failed adding a default object for key [" + key + "]", e);
            }
        }

            return object;
        }

        @FunctionalInterface
        private interface ExperimentalOptionsPatch {
            void applyTo(JSONObject experimentalOptions) throws JSONException;
        }

        private final org.chromium.net.ExperimentalCronetEngine.Builder mBackend;
        private final List<ExperimentalOptionsPatch> mExperimentalOptionsPatches =
                new ArrayList<>();
        private JSONObject mParsedExperimentalOptions = new JSONObject();
    }

    /**
     * @return a human-readable version string of the engine.
     */
    @NonNull
    public static String getVersionString() {
        return ApiVersion.getCronetVersion();
    }

    /**
     * Shuts down the {@link HttpEngine} if there are no active requests,
     * otherwise throws an exception.
     *
     * Cannot be called on network thread - the thread the HTTP stack calls into
     * Executor on (which is different from the thread the Executor invokes
     * callbacks on). May block until all the {@link HttpEngine} resources have been cleaned up.
     */
    public abstract void shutdown();

    /**
     * Binds the engine to the specified network. All requests created through this engine
     * will use the network associated to this handle. If this network disconnects all requests will
     * fail, the exact error will depend on the stage of request processing when the network
     * disconnects.
     *
     * @param network the network to bind the engine to. Specify {@code null} to unbind.
     */
    public void bindToNetwork(@Nullable Network network) {}

    /**
     * Establishes a new connection to the resource specified by the {@link URL} {@code url}.
     * <p>
     * <b>Note:</b> This {@link java.net.HttpURLConnection} implementation is subject to certain
     * limitations, see {@link #createUrlStreamHandlerFactory} for details.
     *
     * @param url URL of resource to connect to.
     * @return an {@link java.net.HttpURLConnection} instance implemented
     *     by this {@link HttpEngine}.
     * @throws IOException if an error occurs while opening the connection.
     */
    // SuppressLint since this is for interface parity with j.n.URLConnection
    @SuppressLint("AndroidUri") @NonNull
    public abstract URLConnection openConnection(
            @SuppressLint("AndroidUri") @NonNull URL url) throws IOException;

    /**
     * Creates a {@link URLStreamHandlerFactory} to handle HTTP and HTTPS
     * traffic. An instance of this class can be installed via
     * {@link URL#setURLStreamHandlerFactory} thus using this {@link HttpEngine} by default for
     * all requests created via {@link URL#openConnection}.
     * <p>
     * This {@link java.net.HttpURLConnection} implementation does not implement all features
     * offered by the API:
     * <ul>
     * <li>the HTTP cache installed via
     *     {@link HttpResponseCache#install(java.io.File, long) HttpResponseCache.install()}</li>
     * <li>the HTTP authentication method installed via
     *     {@link java.net.Authenticator#setDefault}</li>
     * <li>the HTTP cookie storage installed via {@link java.net.CookieHandler#setDefault}</li>
     * </ul>
     * <p>
     * While we support and encourages requests using the HTTPS protocol, we don't provide support
     * for the {@link HttpsURLConnection} API. This lack of support also includes not using certain
     * HTTPS features provided via {@link HttpsURLConnection}:
     * <ul>
     * <li>the HTTPS hostname verifier installed via {@link
     *   HttpsURLConnection#setDefaultHostnameVerifier(javax.net.ssl.HostnameVerifier)
     *     HttpsURLConnection.setDefaultHostnameVerifier()}</li>
     * <li>the HTTPS socket factory installed via {@link
     *   HttpsURLConnection#setDefaultSSLSocketFactory(javax.net.ssl.SSLSocketFactory)
     *     HttpsURLConnection.setDefaultSSLSocketFactory()}</li>
     * </ul>
     *
     * @return an {@link URLStreamHandlerFactory} instance implemented by this
     *         {@link HttpEngine}.
     */
    // SuppressLint since this is for interface parity with j.n.URLStreamHandlerFactory
    @SuppressLint("AndroidUri") @NonNull
    public abstract URLStreamHandlerFactory createUrlStreamHandlerFactory();

    /**
     * Creates a builder for {@link UrlRequest}. All callbacks for
     * generated {@link UrlRequest} objects will be invoked on
     * {@code executor}'s threads. {@code executor} must not run tasks on the
     * thread calling {@link Executor#execute} to prevent blocking networking
     * operations and causing exceptions during shutdown.
     *
     * @param url URL for the generated requests.
     * @param executor {@link Executor} on which all callbacks will be invoked.
     * @param callback callback object that gets invoked on different events.
     */
    @NonNull
    public abstract UrlRequest.Builder newUrlRequestBuilder(
            @NonNull String url, @NonNull Executor executor, @NonNull UrlRequest.Callback callback);

    /**
     * Creates a builder for {@link BidirectionalStream} objects. All callbacks for
     * generated {@code BidirectionalStream} objects will be invoked on
     * {@code executor}. {@code executor} must not run tasks on the
     * current thread, otherwise the networking operations may block and exceptions
     * may be thrown at shutdown time.
     *
     * @param url URL for the generated streams.
     * @param executor the {@link Executor} on which {@code callback} methods will be invoked.
     * @param callback the {@link BidirectionalStream.Callback} object that gets invoked upon
     * different events occurring.
     *
     * @return the created builder.
     */
    @NonNull
    public abstract BidirectionalStream.Builder newBidirectionalStreamBuilder(
            @NonNull String url, @NonNull Executor executor,
            @NonNull BidirectionalStream.Callback callback);

}
