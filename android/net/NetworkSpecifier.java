/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.annotation.SystemApi.Client.PRIVILEGED_APPS;

import static com.android.net.thread.platform.flags.Flags.FLAG_THREAD_MOBILE_ENABLED;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Describes specific properties of a requested network for use in a {@link NetworkRequest}.
 *
 * This as an abstract class. Applications shouldn't instantiate this class by themselves, but can
 * obtain instances of subclasses of this class via other APIs.
 */
public abstract class NetworkSpecifier {
    /**
     * Create a placeholder object. Please use subclasses of this class in a {@link NetworkRequest}
     * to request a network.
     */
    public NetworkSpecifier() {}

    /**
     * Returns true if a request with this {@link NetworkSpecifier} is satisfied by a network
     * with the given NetworkSpecifier.
     *
     * @hide
     */
    @SystemApi
    public boolean canBeSatisfiedBy(@Nullable NetworkSpecifier other) {
        return false;
    }

    /**
     * Optional method which can be overridden by concrete implementations of NetworkSpecifier to
     * perform any redaction of information from the NetworkSpecifier, e.g. if it contains
     * sensitive information. The default implementation simply returns the object itself - i.e.
     * no information is redacted. A concrete implementation may return a modified (copy) of the
     * NetworkSpecifier, or even return a null to fully remove all information.
     * <p>
     * This method is relevant to NetworkSpecifier objects used by agents - those are shared with
     * apps by default. Some agents may store sensitive matching information in the specifier,
     * e.g. a Wi-Fi SSID (which should not be shared since it may leak location). Those classes
     * can redact to a null. Other agents use the Network Specifier to share public information
     * with apps - those should not be redacted.
     * <p>
     * The default implementation redacts no information.
     * <p>
     * New NetworkSpecifier subclasses should not override this method, and should instead override
     * {@link #getApplicableRedactions()} and {@link #redact(long)}.
     *
     * @return A NetworkSpecifier object to be passed along to the requesting app.
     *
     * @hide
     */
    @SystemApi
    @Nullable
    public NetworkSpecifier redact() {
        // TODO (b/122160111): convert default to null once all platform NetworkSpecifiers
        // implement this method.
        return this;
    }

    /**
     * Optional method which can be overridden by concrete implementations of NetworkSpecifier to
     * perform any redaction of information from the NetworkSpecifier, e.g. if it contains
     * sensitive information. The default implementation simply returns the object itself - i.e.
     * no information is redacted. A concrete implementation may return a modified (copy) of the
     * NetworkSpecifier, or even return a null to fully remove all information.
     * <p>
     * This method is relevant to NetworkSpecifier objects used by agents - those are shared with
     * apps by default. Some agents may store sensitive matching information in the specifier,
     * e.g. a Wi-Fi SSID (which should not be shared since it may leak location). Those classes
     * can redact to a null. Other agents use the Network Specifier to share public information
     * with apps - those should not be redacted.
     * <p>
     * When a NetworkSpecifier is used in the input of ConnectivityManager APIs (e.g. {@link
     * ConnectivityManager#requestNetwork} and {@link ConnectivityManager#registerNetworkCallback}),
     * this method will be used to check if the {@code NetworkRequest} contains sensitive
     * information that the requesting app doesn't have permission to use and throws {@link
     * SecurityException} on it. When the NetworkSpecifier is included in {@link
     * ConnectivityManager#NetworkCallback}, this method will be used to redact sensitive
     * information that the receiving app doesn't have permission to see it.
     * <p>
     * The default implementation will do nothing and return {@code this}.
     * <p>
     * Instead of overriding {@link #redact()}, a new subclass should override this method and
     * {@link #getApplicableRedactions()}. And this method may be called with redactions that were
     * not returned by {@link #getApplicableRedactions()}. Such redactions should be silently
     * ignored.
     *
     * @param redactions see {@link NetworkCapabilities#REDACT_} for available redactions. For
     * example, if the bit {@link NetworkCapabilities#REDACT_FOR_ACCESS_FINE_LOCATION} is set in
     * this argument, the returned specifier should contain no information that should not be
     * shared with apps that do not hold the ACCESS_FINE_LOCATION permission.
     * @return a NetworkSpecifier object to be passed along to the requesting app
     * @see #getApplicableRedactions()
     * @hide
     */
    @FlaggedApi(FLAG_THREAD_MOBILE_ENABLED)
    @SystemApi(client = PRIVILEGED_APPS)
    @Nullable
    public NetworkSpecifier redact(long redactions) {
        return this;
    }

    /**
     * Returns a bitmask of all the applicable redactions (based on the permissions held by the
     * receiving app) to be performed on this NetworkSpecifier.
     * <p>
     * The default implementation returns {@link NetworkCapabilities#REDACT_NONE}.
     * <p>
     * A subclass which is overriding this API MUST override {@link #redact(long)} as well..
     *
     * @return bitmask of redactions that this specifier is subject to. For example, if a specifier
     * has information that should only be shared with apps holding the ACCESS_FINE_LOCATION
     * permission and the WHATEVER permission, it should return {@code
     * REDACT_FOR_ACCESS_FINE_LOCATION|REDACT_FOR_WHATEVER}.
     * @see #redact(long)
     * @hide
     */
    @FlaggedApi(FLAG_THREAD_MOBILE_ENABLED)
    @SystemApi(client = PRIVILEGED_APPS)
    public long getApplicableRedactions() {
        return NetworkCapabilities.REDACT_NONE;
    }
}
