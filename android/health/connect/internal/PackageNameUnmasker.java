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
 * An interface for objects that can convert themselves from a masked package name representation
 * back into their canonical form. This is a crucial component within Health Connect's Device Data
 * Provider (DDP) system. Its primary role is to reverse the application-scoped package name masking
 * applied for client applications, thereby allowing Health Connect to accurately map these
 * client-specific identifiers back to the underlying canonical device identifiers. Thus, failure to
 * unmask client requests will lead to internal data misses for device data.
 *
 * @param <T> The type of the object itself, allowing for either in-place mutation or the return of
 *     a new instance of the same type with the unmasked package name.
 * @hide
 */
public interface PackageNameUnmasker<T> {
    /**
     * Converts the object's masked, client-specific package name back into its internal, canonical
     * package name using the provided unmasking function.
     *
     * <p>Despite the contract of this method returning an object, implementers can decide if they
     * mutate themselves or return a new instance, preferably shallow-copied for non-package name
     * related fields. In any unmasking scenario, you MUST use the return value of this method for
     * subsequent operations instead of the original one to ensure that operations are performed on
     * the correctly unmasked object.
     *
     * @param packageUnmasker A function that takes a masked package name (String) and returns its
     *     canonical representation (String). See {@link
     *     com.android.server.healthconnect.common.metadata.SyntheticPackageNameResolver} for
     *     potential usage.
     * @return An instance of {@code T}, with its package name transformed into its unmasked,
     *     canonical form.
     */
    @NonNull
    T toUnmasked(@NonNull Function<String, String> packageUnmasker);
}
