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

package android.app.admin.metadata;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.admin.PolicyIdentifier;
import java.util.Set;

/**
 * Class that contains static information about a string policy.
 *
 * @hide
 */
public class StringPolicyMetadata extends PolicyMetadata<String> {
    private final boolean mEmptyStringAllowed;

    public StringPolicyMetadata(
            @NonNull PolicyIdentifier<String> id,
            @NonNull Set<Integer> allowedScopes,
            int affectedResource,
            @Nullable String requiredPermission,
            @Nullable String requiredCrossUserPermission,
            @NonNull Set<Integer> allowedDpcTypes,
            boolean emptyStringAllowed) {
        this(
                id,
                allowedScopes,
                affectedResource,
                requiredPermission,
                requiredCrossUserPermission,
                allowedDpcTypes,
                null,
                emptyStringAllowed);
    }

    public StringPolicyMetadata(
            @NonNull PolicyIdentifier<String> id,
            @NonNull Set<Integer> allowedScopes,
            int affectedResource,
            @Nullable String requiredPermission,
            @Nullable String requiredCrossUserPermission,
            @NonNull Set<Integer> allowedDpcTypes,
            @Nullable ResolutionMechanismMetadata<String> resolutionMechanism,
            boolean emptyStringAllowed) {
        super(
                id,
                allowedScopes,
                affectedResource,
                requiredPermission,
                requiredCrossUserPermission,
                allowedDpcTypes,
                resolutionMechanism);

        mEmptyStringAllowed = emptyStringAllowed;
    }

    public boolean isEmptyStringAllowed() {
        return mEmptyStringAllowed;
    }
}
