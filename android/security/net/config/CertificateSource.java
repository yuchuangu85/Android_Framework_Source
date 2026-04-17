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

package android.security.net.config;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.security.cert.X509Certificate;
import java.util.Set;

/** @hide */
public interface CertificateSource {
    /**
     * Returns all certificates in this source.
     *
     * @return A set containing all certificates.
     */
    @NonNull Set<X509Certificate> getCertificates();

    /**
     * Returns a certificate with the same subject and public key as the provided certificate.
     *
     * @param cert The certificate to match against.
     * @return The matching certificate, or {@code null} if not found.
     */
    @Nullable X509Certificate findBySubjectAndPublicKey(@NonNull X509Certificate cert);

    /**
     * Returns a certificate that is issued by and signed by the provided certificate.
     *
     * @param cert The certificate to match against.
     * @return The matching certificate, or {@code null} if not found.
     */
    @Nullable X509Certificate findByIssuerAndSignature(@NonNull X509Certificate cert);

    /**
     * Returns a set of certificates that are issued by and signed by the provided certificate.
     *
     * @param cert The certificate to match against.
     * @return A set of matching certificates.
     */
    @NonNull Set<X509Certificate> findAllByIssuerAndSignature(@NonNull X509Certificate cert);

    /**
     * Handle an update to the system or user certificate stores.
     */
    void handleTrustStorageUpdate();
}
