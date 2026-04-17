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
 * Class that contains static information about a policy.
 *
 * @param <T> The type for this policy.
 * @hide
 */
public abstract class PolicyMetadata<T> {
    @NonNull private final PolicyIdentifier<T> mId;
    @NonNull private final Set<Integer> mAllowedScopes;
    private final int mAffectedResource;
    @Nullable private final String mRequiredPermission;
    @Nullable private final String mRequiredCrossUserPermission;
    @NonNull private final Set<Integer> mAllowedDpcTypes;
    @Nullable private final ResolutionMechanismMetadata<T> mResolutionMechanism;

    public PolicyMetadata(
            @NonNull PolicyIdentifier<T> id,
            @NonNull Set<Integer> allowedScopes,
            int affectedResource,
            @Nullable String requiredPermission,
            @Nullable String requiredCrossUserPermission,
            @NonNull Set<Integer> allowedDpcTypes,
            @Nullable ResolutionMechanismMetadata<T> resolutionMechanism) {
        this.mId = id;
        this.mAllowedScopes = allowedScopes;
        this.mAffectedResource = affectedResource;
        this.mRequiredPermission = requiredPermission;
        this.mRequiredCrossUserPermission = requiredCrossUserPermission;
        this.mAllowedDpcTypes = allowedDpcTypes;
        this.mResolutionMechanism = resolutionMechanism;
    }

    @NonNull
    public PolicyIdentifier<T> getId() {
        return mId;
    }

    @NonNull
    public Set<Integer> getAllowedScopes() {
        return mAllowedScopes;
    }

    public int getAffectedResource() {
        return mAffectedResource;
    }

    @Nullable
    public String getRequiredCrossUserPermission() {
        return mRequiredCrossUserPermission;
    }

    @Nullable
    public String getRequiredPermission() {
        return mRequiredPermission;
    }

    @NonNull
    public Set<Integer> getAllowedDpcTypes() {
        return mAllowedDpcTypes;
    }

    @Nullable
    public ResolutionMechanismMetadata<T> getResolutionMechanism() {
        return mResolutionMechanism;
    }

    protected String toAttributes() {
        return "mId="
                + mId
                + ", mAllowedScopes="
                + mAllowedScopes
                + ", mAffectedResource="
                + mAffectedResource
                + ", mRequiredPermission="
                + mRequiredPermission
                + ", mRequiredCrossUserPermission="
                + mRequiredCrossUserPermission
                + ", mAllowedScopes="
                + mAllowedScopes
                + ", mAllowedDpcTypes="
                + mAllowedDpcTypes
                + ", mResolutionMechanism="
                + mResolutionMechanism;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + toAttributes() + '}';
    }
}
