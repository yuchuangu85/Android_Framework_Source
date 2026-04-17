/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.health.connect.internal.datatypes.utils;

import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_UNKNOWN;

import android.annotation.Nullable;
import android.health.connect.HealthDataCategory;
import android.health.connect.HealthPermissionCategory;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.RecordTypeIdentifier;
import android.health.connect.datatypes.RecordTypeSensitivity;
import android.health.connect.internal.datatypes.RecordInternal;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** @hide */
public class DataTypeDescriptor {
    @RecordTypeIdentifier.RecordType private final int mRecordTypeIdentifier;
    @HealthDataCategory.Type private final int mDataCategory;
    private final Set<PermissionCategory> mPermissionCategories;
    private final Class<? extends RecordInternal<?>> mRecordInternalClass;
    private final Class<? extends Record> mRecordClass;
    @RecordTypeSensitivity.Sensitivity private final int mRecordTypeSensitivity;

    /** A class to hold the permission category which includes read and write permissions. */
    public record PermissionCategory(
            @HealthPermissionCategory.Type int permissionCategoryId,
            String readPermission,
            String writePermission) {}

    private DataTypeDescriptor(Builder builder) {
        checkArgument(builder.mRecordTypeIdentifier != RECORD_TYPE_UNKNOWN, "Unknown record type");
        checkArgument(
                !builder.mPermissionCategories.isEmpty(), "Permission categories cannot be empty");
        checkArgument(
                builder.mHealthDataCategory != HealthDataCategory.UNKNOWN,
                "Unknown health data category");
        mRecordTypeIdentifier = builder.mRecordTypeIdentifier;
        mPermissionCategories = builder.mPermissionCategories;
        mDataCategory = builder.mHealthDataCategory;
        mRecordInternalClass = Objects.requireNonNull(builder.mRecordInternalClass);
        mRecordClass = Objects.requireNonNull(builder.mRecordClass);
        mRecordTypeSensitivity = builder.mRecordTypeSensitivity;
    }

    @RecordTypeIdentifier.RecordType
    public int getRecordTypeIdentifier() {
        return mRecordTypeIdentifier;
    }

    public Set<PermissionCategory> getPermissionCategories() {
        return mPermissionCategories;
    }

    @HealthDataCategory.Type
    public int getDataCategory() {
        return mDataCategory;
    }

    public Class<? extends RecordInternal<?>> getRecordInternalClass() {
        return mRecordInternalClass;
    }

    public Class<? extends Record> getRecordClass() {
        return mRecordClass;
    }

    @RecordTypeSensitivity.Sensitivity
    public int getRecordTypeSensitivity() {
        return mRecordTypeSensitivity;
    }

    interface RecordTypeIdentifierBuilderStep {
        DataCategoryBuilderStep setRecordTypeIdentifier(
                @RecordTypeIdentifier.RecordType int recordTypeIdentifier);
    }

    interface DataCategoryBuilderStep {
        RecordClassBuilderStep setDataCategory(@HealthDataCategory.Type int healthDataCategory);
    }

    interface RecordClassBuilderStep {
        RecordInternalClassBuilderStep setRecordClass(Class<? extends Record> recordClass);
    }

    interface RecordInternalClassBuilderStep {
        RecordTypeSensitivityBuilderStep setRecordInternalClass(
                Class<? extends RecordInternal<?>> recordInternalClass);
    }

    interface RecordTypeSensitivityBuilderStep {
        PermissionCategoryBuilderStep setRecordTypeSensitivity(
                @RecordTypeSensitivity.Sensitivity int sensitivity);
    }

    interface PermissionCategoryBuilderStep {
        PermissionCategoryBuilderStep addPermissionCategory(
                @HealthPermissionCategory.Type int permissionCategoryId,
                String readPermission,
                String writePermission);

        DataTypeDescriptor build();
    }

    static RecordTypeIdentifierBuilderStep builder() {
        return new Builder();
    }

    /* Using the step builder pattern to make the builder compile time safe. */
    static class Builder
            implements RecordTypeIdentifierBuilderStep,
                    DataCategoryBuilderStep,
                    RecordClassBuilderStep,
                    RecordInternalClassBuilderStep,
                    PermissionCategoryBuilderStep,
                    RecordTypeSensitivityBuilderStep {
        @RecordTypeIdentifier.RecordType private int mRecordTypeIdentifier = RECORD_TYPE_UNKNOWN;

        @HealthDataCategory.Type private int mHealthDataCategory = HealthDataCategory.UNKNOWN;
        @Nullable private Class<? extends Record> mRecordClass;
        @Nullable private Class<? extends RecordInternal<?>> mRecordInternalClass;
        private final Set<PermissionCategory> mPermissionCategories = new HashSet<>();

        @RecordTypeSensitivity.Sensitivity
        private int mRecordTypeSensitivity = RecordTypeSensitivity.SENSITIVE;

        private Builder() {}

        @Override
        public DataCategoryBuilderStep setRecordTypeIdentifier(
                @RecordTypeIdentifier.RecordType int recordTypeIdentifier) {
            checkArgument(
                    recordTypeIdentifier != HealthPermissionCategory.UNKNOWN,
                    "Unknown record type identifier");
            mRecordTypeIdentifier = recordTypeIdentifier;
            return this;
        }

        @Override
        public RecordClassBuilderStep setDataCategory(
                @HealthDataCategory.Type int healthDataCategory) {
            checkArgument(
                    healthDataCategory != HealthDataCategory.UNKNOWN,
                    "Unknown health data category");
            mHealthDataCategory = healthDataCategory;
            return this;
        }

        @Override
        public RecordInternalClassBuilderStep setRecordClass(Class<? extends Record> recordClass) {
            mRecordClass = Objects.requireNonNull(recordClass);
            return this;
        }

        @Override
        public RecordTypeSensitivityBuilderStep setRecordInternalClass(
                Class<? extends RecordInternal<?>> recordInternalClass) {
            mRecordInternalClass = Objects.requireNonNull(recordInternalClass);
            return this;
        }

        @Override
        public PermissionCategoryBuilderStep setRecordTypeSensitivity(
                @RecordTypeSensitivity.Sensitivity int sensitivity) {
            mRecordTypeSensitivity = sensitivity;
            return this;
        }

        /** Adds a permission category to the descriptor. */
        @Override
        public PermissionCategoryBuilderStep addPermissionCategory(
                @HealthPermissionCategory.Type int permissionCategoryId,
                String readPermission,
                String writePermission) {
            mPermissionCategories.add(
                    new PermissionCategory(permissionCategoryId, readPermission, writePermission));
            return this;
        }

        @Override
        public DataTypeDescriptor build() {
            return new DataTypeDescriptor(this);
        }
    }

    private static void checkArgument(boolean expression, String errorMsg) {
        if (!expression) {
            throw new IllegalArgumentException(errorMsg);
        }
    }
}
