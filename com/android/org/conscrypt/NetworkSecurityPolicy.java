/* GENERATED SOURCE. DO NOT MODIFY. */
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

package com.android.org.conscrypt;

import com.android.org.conscrypt.metrics.CertificateTransparencyVerificationReason;

/**
 * A policy provided by the platform to decide on the behaviour of TrustManagerImpl.
 *
 * See the platform-specific implementations in PlatformNetworkSecurityPolicy.
 * @hide This class is not part of the Android public SDK API
 */
@Internal
public interface NetworkSecurityPolicy {
    boolean isCertificateTransparencyVerificationRequired(String hostname);

    CertificateTransparencyVerificationReason getCertificateTransparencyVerificationReason(
            String hostname);

    DomainEncryptionMode getDomainEncryptionMode(String hostname);
}
