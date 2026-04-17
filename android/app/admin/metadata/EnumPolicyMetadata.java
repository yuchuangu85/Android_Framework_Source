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
 * Class that contains static information about an enum policy.
 *
 * @hide
 */
public class EnumPolicyMetadata extends PolicyMetadata<Integer> {
    @NonNull private final Set<Integer> mAllowedValues;

    public EnumPolicyMetadata(
            @NonNull PolicyIdentifier<Integer> id,
            @NonNull Set<Integer> allowedScopes,
            int affectedResource,
            @Nullable String requiredPermission,
            @Nullable String requiredCrossUserPermission,
            @NonNull Set<Integer> allowedDpcTypes,
            @Nullable ResolutionMechanismMetadata resolutionMechanism,
            @NonNull Set<Integer> allowedValues) {
        super(
                id,
                allowedScopes,
                affectedResource,
                requiredPermission,
                requiredCrossUserPermission,
                allowedDpcTypes,
                resolutionMechanism);
        mAllowedValues = allowedValues;
    }

    @Override
    public String toString() {
        return "EnumPolicyMetadata{" + toAttributes() + "mAllowedValues=" + mAllowedValues + "} ";
    }

    @NonNull
    public Set<Integer> getAllowedValues() {
        return mAllowedValues;
    }
}
