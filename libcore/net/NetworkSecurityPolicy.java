/*
 * Copyright (C) 2015 The Android Open Source Project
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

package libcore.net;

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import libcore.util.NonNull;

import android.annotation.IntDef;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Network security policy for this process/application.
 *
 * <p>Network stacks/components are expected to honor this policy. Components which can use the
 * Android framework API should be accessing this policy via the framework's
 * {@code android.security.NetworkSecurityPolicy} instead of via this class.
 *
 * <p>The policy can be determined by the {@link #isCleartextTrafficPermitted()},
 * {@link #isCleartextTrafficPermitted(String)} and
 * {@link #isCertificateTransparencyVerificationRequired(String)} methods.
 *
 * @hide
 */
@SystemApi(client = MODULE_LIBRARIES)
@libcore.api.IntraCoreApi
public abstract class NetworkSecurityPolicy {

    private static volatile NetworkSecurityPolicy instance = new DefaultNetworkSecurityPolicy();

    /**
     * Constructs a default {@code NetworkSecurityPolicy}.
     *
     * @see {@link #DefaultNetworkSecurityPolicy}.
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public NetworkSecurityPolicy() {
    }

    /**
     * Gets current singleton {@code NetworkSecurityPolicy} instance.
     *
     * @return the current {@code NetworkSecurityPolicy}.
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @libcore.api.IntraCoreApi
    public static NetworkSecurityPolicy getInstance() {
        return instance;
    }

    /**
     * Sets current singleton instance
     *
     * @param policy new {@code NetworlSecurityPolicy} instance.
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public static void setInstance(NetworkSecurityPolicy policy) {
        if (policy == null) {
            throw new NullPointerException("policy == null");
        }
        instance = policy;
    }

    /**
     * Returns {@code true} if cleartext network traffic (e.g. HTTP, FTP, XMPP, IMAP, SMTP --
     * without TLS or STARTTLS) is permitted for all network communications of this process.
     *
     * <p>{@link #isCleartextTrafficPermitted(String)} should be used to determine if cleartext
     * traffic is permitted for a specific host.
     *
     * <p>When cleartext network traffic is not permitted, the platform's components (e.g. HTTP
     * stacks, {@code WebView}, {@code MediaPlayer}) will refuse this process's requests to use
     * cleartext traffic. Third-party libraries are encouraged to do the same.
     *
     * <p>This flag is honored on a best effort basis because it's impossible to prevent all
     * cleartext traffic from an application given the level of access provided to applications on
     * Android. For example, there's no expectation that {@link java.net.Socket} API will honor this
     * flag. Luckily, most network traffic from apps is handled by higher-level network stacks which
     * can be made to honor this flag. Platform-provided network stacks (e.g. HTTP and FTP) honor
     * this flag from day one, and well-established third-party network stacks will eventually
     * honor it.
     *
     * @return {@code true} if cleartext traffic is permitted and {@code false} otherwise.
     *
     * @hide
     */
    @UnsupportedAppUsage
    @SystemApi(client = MODULE_LIBRARIES)
    public abstract boolean isCleartextTrafficPermitted();

    /**
     * Returns {@code true} if cleartext network traffic (e.g. HTTP, FTP, XMPP, IMAP, SMTP --
     * without TLS or STARTTLS) is permitted for communicating with {@code hostname} for this
     * process.
     *
     * <p>See {@link #isCleartextTrafficPermitted} for more details.
     *
     * @param hostname hostname to check if cleartext traffic is permitted for
     * @return {@code true} if cleartext traffic is permitted and {@code false} otherwise
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    public abstract boolean isCleartextTrafficPermitted(String hostname);

    /**
     * Returns {@code true} if Certificate Transparency information is required to be presented by
     * the server and verified by the client in TLS connections to {@code hostname}.
     *
     * <p>See RFC6962 section 3.3 for more details.
     *
     * @param hostname hostname to check whether certificate transparency verification
     *                 is required
     * @return {@code true} if certificate transparency verification is required and
     *         {@code false} otherwise
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @libcore.api.IntraCoreApi
    public abstract boolean isCertificateTransparencyVerificationRequired(String hostname);

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"CERTIFICATE_TRANSPARENCY_REASON_"}, value = {
        CERTIFICATE_TRANSPARENCY_REASON_UNKNOWN,
        CERTIFICATE_TRANSPARENCY_REASON_SDK_TARGET_DEFAULT_ENABLED,
        CERTIFICATE_TRANSPARENCY_REASON_APP_OPT_IN,
        CERTIFICATE_TRANSPARENCY_REASON_DOMAIN_OPT_IN
    })
    public @interface CertificateTransparencyReason {}

    /**
     * Unknown reason for why Certificate Transparency validation was required.
     */
    @libcore.api.IntraCoreApi
    public static final int CERTIFICATE_TRANSPARENCY_REASON_UNKNOWN = 0;

    /**
     * Certificate Transparency validation was required because it is enabled by default and the
     * app satisfies the selection criteria (i.e., its TargetSdkVersion is at least 37).
     */
    @libcore.api.IntraCoreApi
    public static final int CERTIFICATE_TRANSPARENCY_REASON_SDK_TARGET_DEFAULT_ENABLED = 1;

    /**
     * Certificate Transparency validation was required because the app opted-in for all its
     * connections.
     */
    @libcore.api.IntraCoreApi
    public static final int CERTIFICATE_TRANSPARENCY_REASON_APP_OPT_IN = 2;

    /**
     * Certificate Transparency validation was required because the app opted-in for this specific
     * domain (via its Network Security Config).
     */
    @libcore.api.IntraCoreApi
    public static final int CERTIFICATE_TRANSPARENCY_REASON_DOMAIN_OPT_IN = 3;

    /**
     * Returns the reason why Certificate Transparency was required.
     *
     * <p>If the verification was not required (i.e., isCertificateTransparencyVerificationRequired
     * returns false), return CERTIFICATE_TRANSPARENCY_REASON_UNKNOWN.
     *
     * <p>This method should be overridden by any subclass to return the exact reason.
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @libcore.api.IntraCoreApi
    @CertificateTransparencyReason
    public int getCertificateTransparencyVerificationReason(@NonNull String hostname) {
        return CERTIFICATE_TRANSPARENCY_REASON_UNKNOWN;
    }

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"DOMAIN_ENCRYPTION_"}, value = {
        DOMAIN_ENCRYPTION_MODE_UNKNOWN,
        DOMAIN_ENCRYPTION_MODE_DISABLED,
        DOMAIN_ENCRYPTION_MODE_OPPORTUNISTIC,
        DOMAIN_ENCRYPTION_MODE_ENABLED
    })
    public @interface DomainEncryptionMode {}

    /**
     * Unknown setting for domain encryption in the app.
     *
     * <p>This is the default value returned by {@link #getDomainEncryptionMode(String)} when not
     * overridden. Network libraries should avoid performing any domain encryption and perform a
     * standard TLS handshake, equivalent to {@link #DOMAIN_ENCRYPTION_MODE_DISABLED}.
     */
    @libcore.api.IntraCoreApi
    public static final int DOMAIN_ENCRYPTION_MODE_UNKNOWN = 0;

    /**
     * Domain encryption is disabled for the app. ECH and GREASE should not be used.
     */
    @libcore.api.IntraCoreApi
    public static final int DOMAIN_ENCRYPTION_MODE_DISABLED = 1;

    /**
     * Domain encryption is in opportunistic mode for the app. ECH will only be enabled when there
     * is server support, and GREASE will not be used.
     */
    @libcore.api.IntraCoreApi
    public static final int DOMAIN_ENCRYPTION_MODE_OPPORTUNISTIC = 2;

    /**
     * Domain encryption is in fully enabled mode for the app. ECH will be enabled when there is
     * server support, otherwise GREASE will be used.
     */
    @libcore.api.IntraCoreApi
    public static final int DOMAIN_ENCRYPTION_MODE_ENABLED = 3;

    /**
     * Domain encryption is required for the app and should fail closed (i.e. if encryption cannot
     * be enabled for any reason, the connection will fail).
     */
    @libcore.api.IntraCoreApi
    // TODO(b/476104302): bump the visibility back to public API for 26Q4
    static final int DOMAIN_ENCRYPTION_MODE_REQUIRED = 4;

    /**
     * Returns the domain encryption mode (including ECH).
     *
     * <p>This method should be overridden by any subclass to return the exact mode.
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @DomainEncryptionMode
    @libcore.api.IntraCoreApi
    public int getDomainEncryptionMode(@NonNull String hostname) {
        return DOMAIN_ENCRYPTION_MODE_UNKNOWN;
    }

    /**
     * Default network security policy that allows cleartext traffic and does not require
     * certificate transparency verification.
     *
     * @hide
     */
    public static final class DefaultNetworkSecurityPolicy extends NetworkSecurityPolicy {
        @Override
        public boolean isCleartextTrafficPermitted() {
            return true;
        }

        @Override
        public boolean isCleartextTrafficPermitted(String hostname) {
            return isCleartextTrafficPermitted();
        }

        @Override
        public boolean isCertificateTransparencyVerificationRequired(String hostname) {
            return false;
        }
    }
}
