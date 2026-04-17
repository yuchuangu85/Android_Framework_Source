/*
 * Copyright (C) 2026 The Android Open Source Project
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
 * Class that contains static information about a long policy.
 *
 * @hide
 */
public class LongPolicyMetadata extends PolicyMetadata<Long> {
    private final long mMinValue;
    private final long mMaxValue;

    public LongPolicyMetadata(
            @NonNull PolicyIdentifier<Long> id,
            @NonNull Set<Integer> allowedScopes,
            int affectedResource,
            @Nullable String requiredPermission,
            @Nullable String requiredCrossUserPermission,
            @NonNull Set<Integer> allowedDpcTypes,
            long minValue,
            long maxValue) {
        super(
                id,
                allowedScopes,
                affectedResource,
                requiredPermission,
                requiredCrossUserPermission,
                allowedDpcTypes,
                null);
        mMinValue = minValue;
        mMaxValue = maxValue;
    }

    /**
     * Returns the smallest accepted value for this policy, defaults to {@link Long#MIN_VALUE} if
     * not set.
     */
    public long getMinValue() {
        return mMinValue;
    }

    /**
     * Returns the largest accepted value for this policy, defaults to {@link Long#MAX_VALUE} if not
     * set.
     */
    public long getMaxValue() {
        return mMaxValue;
    }
}
