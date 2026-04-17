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

package android.health.connect.changelog;

import static android.health.connect.datatypes.validation.ValidationUtils.validateIntDefValue;

import static com.android.healthfitness.flags.AconfigFlagHelper.isPhrChangeLogsEnabled;
import static com.android.healthfitness.flags.Flags.FLAG_PHR_CHANGE_LOGS;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.health.connect.HealthConnectManager;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.MedicalResource;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.RecordTypeIdentifier;
import android.health.connect.internal.datatypes.utils.HealthConnectMappings;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A class to request changelog token using {@link HealthConnectManager#getChangeLogToken}
 *
 * @see HealthConnectManager#getChangeLogToken
 */
public final class ChangeLogTokenRequest implements Parcelable {
    private final Set<String> mPackageNames;
    private final Set<@RecordTypeIdentifier.RecordType Integer> mRecordTypeIds;
    private final Set<@MedicalResource.MedicalResourceType Integer> mMedicalResourceTypes;

    /**
     * @param packageNames set of package names to filter the data
     * @param recordTypeIds set of record types for which change logs are requested
     * @param medicalResourceTypes set of medical resource types for which change logs are requested
     */
    private ChangeLogTokenRequest(
            @NonNull Set<String> packageNames,
            @NonNull Set<@RecordTypeIdentifier.RecordType Integer> recordTypeIds,
            @NonNull Set<@MedicalResource.MedicalResourceType Integer> medicalResourceTypes) {
        validate(recordTypeIds, medicalResourceTypes);

        mPackageNames = Objects.requireNonNull(packageNames);
        mRecordTypeIds = Objects.requireNonNull(recordTypeIds);
        mMedicalResourceTypes = Objects.requireNonNull(medicalResourceTypes);
    }

    private ChangeLogTokenRequest(@NonNull Parcel in) {
        Set<@RecordTypeIdentifier.RecordType Integer> recordTypeIds = new ArraySet<>();
        for (@RecordTypeIdentifier.RecordType int recordTypeId : in.createIntArray()) {
            recordTypeIds.add(recordTypeId);
        }
        mRecordTypeIds = recordTypeIds;
        mPackageNames = Set.copyOf(in.createStringArrayList());

        Set<@MedicalResource.MedicalResourceType Integer> medicalResourceTypes = new ArraySet<>();
        for (@MedicalResource.MedicalResourceType int resourceType : in.createIntArray()) {
            medicalResourceTypes.add(resourceType);
        }
        mMedicalResourceTypes = medicalResourceTypes;
    }

    @NonNull
    public static final Creator<ChangeLogTokenRequest> CREATOR =
            new Creator<>() {
                @Override
                public ChangeLogTokenRequest createFromParcel(@NonNull Parcel in) {
                    return new ChangeLogTokenRequest(in);
                }

                @Override
                public ChangeLogTokenRequest[] newArray(int size) {
                    return new ChangeLogTokenRequest[size];
                }
            };

    /** Returns a set of data origin filters to filter the change logs */
    @NonNull
    public Set<DataOrigin> getDataOriginFilters() {
        return mPackageNames.stream()
                .map(packageName -> new DataOrigin.Builder().setPackageName(packageName).build())
                .collect(Collectors.toSet());
    }

    /** Returns a set of record classes for which logs are requested */
    @NonNull
    public Set<Class<? extends Record>> getRecordTypes() {
        var map = HealthConnectMappings.getInstance().getRecordIdToExternalRecordClassMap();
        return mRecordTypeIds.stream().map(map::get).collect(Collectors.toSet());
    }

    /** @hide */
    @NonNull
    public Set<String> getPackageNamesToFilter() {
        return mPackageNames;
    }

    /** @hide */
    @NonNull
    public Set<@RecordTypeIdentifier.RecordType Integer> getRecordTypeIds() {
        return mRecordTypeIds;
    }

    /** Returns a set of medical resource types for which logs are requested */
    @NonNull
    @FlaggedApi(FLAG_PHR_CHANGE_LOGS)
    public Set<@MedicalResource.MedicalResourceType Integer> getMedicalResourceTypes() {
        return mMedicalResourceTypes;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeIntArray(toIntArray(mRecordTypeIds));
        dest.writeStringList(List.copyOf(mPackageNames));
        dest.writeIntArray(toIntArray(mMedicalResourceTypes));
    }

    @NonNull
    private int[] toIntArray(Collection<Integer> collection) {
        return collection.stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ChangeLogTokenRequest that)) return false;
        return Objects.equals(mPackageNames, that.mPackageNames)
                && Objects.equals(mRecordTypeIds, that.mRecordTypeIds)
                && Objects.equals(mMedicalResourceTypes, that.mMedicalResourceTypes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPackageNames, mRecordTypeIds, mMedicalResourceTypes);
    }

    /** @hide */
    @NonNull
    public ChangeLogTokenRequest toUnmasked(@NonNull Function<String, String> packageUnmasker) {
        return new ChangeLogTokenRequest(
                mPackageNames.stream().map(packageUnmasker).collect(Collectors.toSet()),
                mRecordTypeIds,
                mMedicalResourceTypes);
    }

    /** Builder for {@link ChangeLogTokenRequest} */
    public static final class Builder {
        /**
         * Stored as class set instead of an integer set for the validation to be done on the whole
         * set, with proper error messages, during {@link #build}.
         */
        private final Set<Class<? extends Record>> mRecordTypes = new ArraySet<>();

        private final Set<String> mPackageNames = new ArraySet<>();
        private final Set<@MedicalResource.MedicalResourceType Integer> mMedicalResourceTypes =
                new ArraySet<>();

        /**
         * Add a Record type to the list of types for which change logs are requested. Record type
         * or Medical Resource types can't both be set.
         */
        @NonNull
        public Builder addRecordType(@NonNull Class<? extends Record> recordType) {
            Objects.requireNonNull(recordType);
            if (isPhrChangeLogsEnabled() && !mMedicalResourceTypes.isEmpty()) {
                throw new IllegalArgumentException(
                        "Record types and Medical Resource types can't both be set");
            }

            mRecordTypes.add(recordType);
            return this;
        }

        /**
         * @param dataOriginFilter list of package names on which to filter the data.
         *     <p>If not set logs from all the sources will be returned
         */
        @NonNull
        public Builder addDataOriginFilter(@NonNull DataOrigin dataOriginFilter) {
            Objects.requireNonNull(dataOriginFilter);

            mPackageNames.add(dataOriginFilter.getPackageName());
            return this;
        }

        /**
         * Add a Medical Resource type to the list of types for which change logs are requested.
         * Record type or Medical Resource types can't both be set.
         */
        @NonNull
        @FlaggedApi(FLAG_PHR_CHANGE_LOGS)
        public Builder addMedicalResourceType(
                @MedicalResource.MedicalResourceType int medicalResourceType) {
            if (!mRecordTypes.isEmpty()) {
                throw new IllegalArgumentException(
                        "Record types and Medical Resource types can't both be set");
            }
            mMedicalResourceTypes.add(medicalResourceType);
            return this;
        }

        /**
         * Returns Object of {@link ChangeLogTokenRequest}
         *
         * @throws IllegalStateException if validation fails:
         *     <ul>
         *       <li>At least one Record type or Medical Resource type must be set
         *       <li>Record type or Medical Resource types can't both be set
         *     </ul>
         */
        @NonNull
        public ChangeLogTokenRequest build() {
            var mappings = HealthConnectMappings.getInstance();

            // Validate on record classes before building for better error messages.
            Set<String> invalidRecordTypes =
                    mRecordTypes.stream()
                            .filter(recordClass -> !mappings.hasRecordType(recordClass))
                            .map(Class::getName)
                            .collect(Collectors.toSet());
            if (!invalidRecordTypes.isEmpty()) {
                throw new IllegalStateException(
                        "Requested record types must not contain any of " + invalidRecordTypes);
            }

            var recordTypeIds =
                    mRecordTypes.stream().map(mappings::getRecordType).collect(Collectors.toSet());
            return new ChangeLogTokenRequest(mPackageNames, recordTypeIds, mMedicalResourceTypes);
        }
    }

    /**
     * Validates the given record type IDs and medical resource types.
     *
     * @param recordTypeIds The set of record type IDs to validate.
     * @param medicalResourceTypes The set of medical resource types to validate.
     * @throws IllegalStateException if:
     *     <ul>
     *       <li>PHR change logs are enabled and both record type IDs and medical resource types are
     *           empty.
     *       <li>PHR change logs are enabled and both record type IDs and medical resource types are
     *           non-empty.
     *       <li>PHR change logs are disabled and record type IDs are empty.
     *     </ul>
     */
    private static void validate(
            @NonNull Set<@RecordTypeIdentifier.RecordType Integer> recordTypeIds,
            @NonNull Set<@MedicalResource.MedicalResourceType Integer> medicalResourceTypes) {
        if (isPhrChangeLogsEnabled()) {
            if (recordTypeIds.isEmpty() && medicalResourceTypes.isEmpty()) {
                throw new IllegalStateException(
                        "At least one Record type or Medical Resource type must be set");
            } else if (!recordTypeIds.isEmpty() && !medicalResourceTypes.isEmpty()) {
                throw new IllegalStateException(
                        "Record types and Medical Resource types can't both be set");
            }
        } else {
            if (recordTypeIds.isEmpty()) {
                throw new IllegalStateException("Requested record types must not be empty");
            }
        }

        try {
            var allRecordTypeIdentifiers =
                    HealthConnectMappings.getInstance().getAllRecordTypeIdentifiers();
            recordTypeIds.forEach(
                    recordTypeId ->
                            validateIntDefValue(
                                    recordTypeId,
                                    allRecordTypeIdentifiers,
                                    RecordTypeIdentifier.RecordType.class.getSimpleName()));
            if (isPhrChangeLogsEnabled()) {
                medicalResourceTypes.forEach(MedicalResource::validateMedicalResourceType);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e);
        }
    }
}
