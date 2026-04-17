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
import android.annotation.SuppressLint;

import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Defines a proxy configuration that can be used by {@link HttpEngine}. */
@FlaggedApi(Flags.FLAG_PROXY_APIS)
// This is named ProxyOptions instead of ProxyParams to maintain consistency with pre-existing
// HttpEngine APIs (e.g., DnsOptions, QuicOptions, etc.).
@SuppressLint("UserHandleName")
public final class ProxyOptions {

    /**
     * Defines behavior after all proxies in a {@link ProxyOptions} have failed.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"ALL_PROXIES_FAILED_BEHAVIOR_"},
            value = {
                ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT,
                ALL_PROXIES_FAILED_BEHAVIOR_ALLOW_DIRECT
            })
    public @interface AllProxiesFailedBehavior {}

    /**
     * Disallows direct traffic.
     *
     * <p>This defines a fail-closed behavior: if all proxies in a {@link ProxyOptions} have failed,
     * HttpEngine won't fall back onto non-proxied connections.
     */
    public static final int ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT = 0;

    /**
     * Allows direct, non-proxied, traffic.
     *
     * <p>This defines a fail-open behavior: if all proxies in a {@link ProxyOptions} have failed,
     * HttpEngine will fall back onto non-proxied connections.
     */
    public static final int ALL_PROXIES_FAILED_BEHAVIOR_ALLOW_DIRECT = 1;

    /**
     * Constructs a proxy configuration out of a list of {@link Proxy}.
     *
     * <p>Proxies in the list will be used in order. Proxy in position N+1 will be used only if
     * HttpEngine failed to use proxy in position N. If proxy in position N fails, for any reason
     * (including tunnel closures triggered via {@link Proxy.HttpConnectCallback}), but proxy in
     * position N+1 succeeds, proxies in position N will be temporarily deprioritized. While a proxy
     * is deprioritized it will be used only as a last resort.
     *
     * @param proxyList The list of {@link Proxy} that defines this configuration.
     * @param allProxiesFailedBehavior How HttpEngine must behave after it has failed to use all
     *     proxies in {@code proxyList}.
     * @throws IllegalArgumentException If the proxy list is empty, or any element is {@code null}.
     */
    @NonNull
    public static ProxyOptions fromProxyList(
            @NonNull List<Proxy> proxyList,
            @AllProxiesFailedBehavior int allProxiesFailedBehavior) {
        return new ProxyOptions(proxyList, allProxiesFailedBehavior);
    }

    private ProxyOptions(
            @NonNull List<Proxy> proxyList,
            @AllProxiesFailedBehavior int allProxiesFailedBehavior) {
        if (Objects.requireNonNull(proxyList).isEmpty()) {
            throw new IllegalArgumentException("proxyList cannot be empty");
        }
        if (proxyList.contains(null)) {
            throw new IllegalArgumentException("Proxies in the list cannot be null");
        }
        mProxyList = Collections.unmodifiableList(new ArrayList<>(proxyList));
        mAllProxiesFailedBehavior =
                switch (allProxiesFailedBehavior) {
                    case ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT,
                            ALL_PROXIES_FAILED_BEHAVIOR_ALLOW_DIRECT ->
                            allProxiesFailedBehavior;
                    default ->
                            throw new IllegalArgumentException(
                                    String.format(
                                            "Unknown allProxiesFailedBehavior %d",
                                            allProxiesFailedBehavior));
                };
    }

    @AllProxiesFailedBehavior
    int getFallbackBehavior() {
        return mAllProxiesFailedBehavior;
    }

    /** Returns the list of proxies that are part of this proxy configuration. */
    @NonNull
    List<Proxy> getProxyList() {
        return mProxyList;
    }

    static void apply(
            @NonNull org.chromium.net.ExperimentalCronetEngine.Builder builder,
            @NonNull ProxyOptions options) {
        List<org.chromium.net.Proxy> proxies = new ArrayList<>();
        for (android.net.http.Proxy proxy : options.getProxyList()) {
            proxies.add(proxy.getBackend());
        }
        switch (options.getFallbackBehavior()) {
            case ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT -> {
                // Nothing to do, this is CronetEngine's default behavior.
            }
            case ALL_PROXIES_FAILED_BEHAVIOR_ALLOW_DIRECT -> proxies.add(null);
            default -> throw new AssertionError("This should never happen");
        }
        builder.setProxyOptions(org.chromium.net.ProxyOptions.fromProxyList(proxies));
    }

    private final @NonNull List<Proxy> mProxyList;
    private final @AllProxiesFailedBehavior int mAllProxiesFailedBehavior;
}
