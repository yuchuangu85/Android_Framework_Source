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
import android.app.admin.PolicyIdentifier;

import java.util.List;

/**
 * Class that contains static information about a list policy.
 * Even though the annotations can not be generic, the policy metadata is generic for lists.
 *
 * @param <T> The type of elements stored in the list.
 * @hide
 */
public class ListPolicyMetadata<T> extends PolicyMetadata<List<T>> {
    @NonNull
    private final PolicyMetadata<T> mElementMetadata;
    private final boolean mEmptyListAllowed;

    public ListPolicyMetadata(
            @NonNull PolicyIdentifier<List<T>> id,
            @NonNull PolicyMetadata<T> elementMetadata,
            boolean emptyListAllowed
    ) {
        super(
                id,
                elementMetadata.getAllowedScopes(),
                elementMetadata.getAffectedResource(),
                elementMetadata.getRequiredPermission(),
                elementMetadata.getRequiredCrossUserPermission(),
                elementMetadata.getAllowedDpcTypes(),
                null
        );

        mElementMetadata = elementMetadata;
        mEmptyListAllowed = emptyListAllowed;
    }

    @NonNull
    public PolicyMetadata<T> getElementMetadata() {

        return mElementMetadata;
    }

    public boolean isEmptyListAllowed() {
        return mEmptyListAllowed;
    }
}
