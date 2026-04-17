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
package android.health.connect.internal;

import android.annotation.NonNull;

import java.util.function.Function;

/**
 * An interface for objects that can convert themselves into a masked representation of their
 * package name. This is a crucial component within Health Connect's Device Data Provider (DDP)
 * system. Its primary role is to generate application-scoped package names from canonical device
 * identifiers, thereby allowing Health Connect to present client-specific identifiers while
 * ensuring privacy and preventing cross-app tracking.
 *
 * <p>As detailed in Functional Requirement 6 and the "Masking SPNs for Clients" section of the <a
 * href="go/ddp-internals-package-names">go/ddp-internals-package-names</a> design document, this
 * masking process is essential for compliance with platform policies and safeguarding user privacy.
 * It ensures that a client application receives a unique identifier for a device that cannot be
 * directly correlated with identifiers received by other applications for the same device.
 *
 * @param <T> The type of the object itself, allowing for either in-place mutation or the return of
 *     a new instance of the same type with the masked package name.
 * @hide
 */
public interface PackageNameMasker<T> {

    /**
     * Converts the object's internal, canonical package name into a masked, client-specific package
     * name using the provided masking function.
     *
     * <p>Despite the contract of this method returning an object, implementers can decide if they
     * mutate themselves or return a new instance, preferably shallow-copied for non-package name *
     * related fields. In any masking scenario, masking the object MUST be the last step on the
     * server side before returning to the client.
     *
     * @param packageMasker A function that takes a masked package name (String) and returns its
     *     canonical representation (String). See {@link
     *     com.android.server.healthconnect.common.metadata.SyntheticPackageNameResolver} for
     *     potential usage.
     * @return An instance of {@code T}, with its package name transformed into its masked,
     *     client-specific form.
     */
    @NonNull
    T toMasked(@NonNull Function<String, String> packageMasker);
}
