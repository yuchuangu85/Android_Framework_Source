/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.health.connect;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.RecordTypeIdentifier;

import com.android.healthfitness.flags.Flags;

import java.util.List;
import java.util.Set;

/**
 * Holder for the following information for each {@link RecordTypeIdentifier.RecordType}:
 *
 * <ul>
 *   <li>{@link HealthPermissionCategory} for the record type.
 *   <li>{@link HealthDataCategory} for the record type.
 *   <li>Packages using this record type.
 * </ul>
 *
 * @hide
 */
@SystemApi
public class RecordTypeInfoResponse {
    private final Set<Integer> mPermissionCategories;
    @HealthDataCategory.Type private final int mDataCategory;
    private final List<DataOrigin> mContributingPackages;

    /**
     * @hide
     * @deprecated use {@link #RecordTypeInfoResponse(Set, int, List)} instead
     */
    @Deprecated
    public RecordTypeInfoResponse(
            @NonNull @HealthPermissionCategory.Type int permissionCategory,
            @HealthDataCategory.Type int dataCategory,
            @NonNull List<DataOrigin> contributingPackages) {
        this(Set.of(permissionCategory), dataCategory, contributingPackages);
    }

    /** @hide */
    public RecordTypeInfoResponse(
            @NonNull Set<Integer> permissionCategories,
            @NonNull @HealthDataCategory.Type int dataCategory,
            @NonNull List<DataOrigin> contributingPackages) {
        this.mPermissionCategories = permissionCategories;
        this.mDataCategory = dataCategory;
        this.mContributingPackages = contributingPackages;
    }

    /**
     * Returns {@link HealthPermissionCategory} for the {@link RecordTypeIdentifier.RecordType}.
     *
     * @deprecated use {@link #getPermissionCategories()} instead. A record can have more than one
     *     permission category associated with it.
     */
    @Deprecated
    @FlaggedApi(Flags.FLAG_SYMPTOMS)
    @HealthPermissionCategory.Type
    public int getPermissionCategory() {
        return mPermissionCategories.iterator().next();
    }

    /**
     * Returns an array of {@link HealthPermissionCategory} for the input {@link
     * RecordTypeIdentifier.RecordType}.
     */
    @NonNull
    @FlaggedApi(Flags.FLAG_SYMPTOMS)
    public @HealthPermissionCategory.Type int[] getPermissionCategories() {
        return mPermissionCategories.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Returns {@link HealthDataCategory} for the {@link RecordTypeIdentifier.RecordType}. */
    @HealthDataCategory.Type
    public int getDataCategory() {
        return mDataCategory;
    }

    /** Returns contributing packages for the {@link RecordTypeIdentifier.RecordType}. */
    @NonNull
    public List<DataOrigin> getContributingPackages() {
        return mContributingPackages;
    }
}
