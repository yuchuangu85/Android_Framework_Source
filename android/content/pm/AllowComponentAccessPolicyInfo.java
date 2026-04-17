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

package android.content.pm;

import android.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * Represents the set of rules, declared in an application's manifest via the
 * {@code <allow-component-access>} tag, that defines which other applications
 * (and their components) the application is permitted to associate with.
 * This object is immutable and is used by core system services to make
 * security decisions about inter-component communication.
 *
 * @hide
 * @see R.styleable#AndroidManifestAllowComponentAccess
 */
public final class AllowComponentAccessPolicyInfo {

    /**
     * The immutable list of {@link SignedPackage} objects that this application
     * is explicitly permitted to associate with, based on manifest rules.
     * Immutability is ensured by wrapping the list with
     * {@link java.util.Collections#unmodifiableList(List)}.
     */
    @NonNull
    private final List<SignedPackage> mAllowlistedSignedPackages;

    public AllowComponentAccessPolicyInfo(@NonNull List<SignedPackage> allowlistedSignedPackages) {
        this.mAllowlistedSignedPackages = Collections.unmodifiableList(
                new ArrayList<>(allowlistedSignedPackages));
    }

    /**
     * Returns the list of {@link SignedPackage} objects that this application
     * is explicitly permitted to associate with, based on manifest rules.
     */
    public @NonNull List<SignedPackage> getAllowlistedSignedPackages() {
        return mAllowlistedSignedPackages;
    }
}

