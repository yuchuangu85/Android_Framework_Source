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

import static android.health.connect.Constants.DEFAULT_INT;
import static android.health.connect.HealthPermissions.READ_EXERCISE_ROUTES;
import static android.health.connect.HealthPermissions.WRITE_EXERCISE_ROUTE;

import static java.util.stream.Collectors.flatMapping;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.health.connect.HealthDataCategory;
import android.health.connect.HealthPermissionCategory;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.RecordTypeIdentifier;
import android.health.connect.datatypes.RecordTypeSensitivity;
import android.health.connect.internal.datatypes.RecordInternal;
import android.health.connect.internal.datatypes.utils.DataTypeDescriptor.PermissionCategory;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/** @hide */
public final class HealthConnectMappings {
    public static final String WRITE = ".WRITE_";
    private final ArrayMap<Integer, DataTypeDescriptor> mRecordIdToDescriptorMap;
    private final ArrayMap<Integer, Set<Integer>> mRecordIdToPermissionCategoriesMap;
    private final ArrayMap<Integer, String> mPermissionCategoryToReadPermissionMap;
    private final ArrayMap<String, Integer> mReadPermissionToPermissionCategoryMap;
    private final ArrayMap<Integer, String> mPermissionCategoryToWritePermissionMap;
    private final ArrayMap<String, Integer> mWritePermissionToDataCategoryMap;
    private final ArrayMap<Integer, String[]> mDataCategoryToWritePermissionsMap;
    private final Map<Integer, Class<? extends RecordInternal<?>>>
            mRecordIdToInternalRecordClassMap;
    private final Map<Integer, Class<? extends Record>> mRecordIdToRecordClassMap;
    private final Map<Class<? extends Record>, Integer> mRecordClassToRecordIdMap;
    private final Set<Integer> mHealthDataCategories;

    @Nullable private static volatile HealthConnectMappings sHealthConnectMappings;

    /** Exists for compatibility with classes which don't support injections yet. */
    // TODO(b/353283052): inject where possible instead of using the singleton.
    public static HealthConnectMappings getInstance() {
        if (sHealthConnectMappings == null) {
            sHealthConnectMappings = new HealthConnectMappings();
        }
        return sHealthConnectMappings;
    }

    /**
     * Resets the singleton instance.
     *
     * <p>Useful for unit tests where flag values might change between test cases.
     */
    @VisibleForTesting
    public static void resetInstanceForTesting() {
        sHealthConnectMappings = new HealthConnectMappings();
    }

    /**
     * Use {@link #getInstance()} to avoid creating multiple instances until it gets migrated off.
     */
    @VisibleForTesting
    public HealthConnectMappings() {
        var dataTypeDescriptors = DataTypeDescriptors.getAllDataTypeDescriptors();

        mRecordIdToDescriptorMap =
                toArrayMap(
                        dataTypeDescriptors,
                        DataTypeDescriptor::getRecordTypeIdentifier,
                        descriptor -> descriptor);

        mRecordIdToPermissionCategoriesMap =
                createRecordIdToPermissionCategoriesMap(dataTypeDescriptors);
        mPermissionCategoryToReadPermissionMap =
                createPermissionCategoryToReadPermissionMap(dataTypeDescriptors);
        mPermissionCategoryToWritePermissionMap =
                createPermissionCategoryToWritePermissionMap(dataTypeDescriptors);
        mReadPermissionToPermissionCategoryMap =
                createReadPermissionToPermissionCategoryMap(dataTypeDescriptors);
        mWritePermissionToDataCategoryMap =
                createWritePermissionToDataCategoryMap(dataTypeDescriptors);

        mDataCategoryToWritePermissionsMap =
                getDataCategoryToWritePermissionsMap(dataTypeDescriptors);

        mRecordIdToInternalRecordClassMap =
                toArrayMap(
                        dataTypeDescriptors,
                        DataTypeDescriptor::getRecordTypeIdentifier,
                        DataTypeDescriptor::getRecordInternalClass);

        mRecordIdToRecordClassMap =
                toArrayMap(
                        dataTypeDescriptors,
                        DataTypeDescriptor::getRecordTypeIdentifier,
                        DataTypeDescriptor::getRecordClass);
        mRecordClassToRecordIdMap =
                toArrayMap(
                        dataTypeDescriptors,
                        DataTypeDescriptor::getRecordClass,
                        DataTypeDescriptor::getRecordTypeIdentifier);

        mHealthDataCategories =
                toArraySet(dataTypeDescriptors, DataTypeDescriptor::getDataCategory);
    }

    /**
     * Returns a set of all supported record type identifiers.
     *
     * @see RecordTypeIdentifier
     */
    public Set<Integer> getAllRecordTypeIdentifiers() {
        return mRecordIdToDescriptorMap.keySet();
    }

    /**
     * @return true if {@code permissionName} is a write-permission
     * @hide
     */
    public boolean isWritePermission(@NonNull String permissionName) {
        return mWritePermissionToDataCategoryMap.containsKey(permissionName);
    }

    /**
     * @return true if {@code permissionName} is a read-permission
     * @hide
     */
    public boolean isReadPermission(@NonNull String permissionName) {
        return mReadPermissionToPermissionCategoryMap.containsKey(permissionName);
    }

    /**
     * @return true if {@code permissionName} is a fitness-permission
     * @hide
     */
    public boolean isFitnessPermission(@NonNull String permissionName) {
        return isReadPermission(permissionName) || isWritePermission(permissionName);
    }

    /** @hide */
    public String getHealthReadPermission(@HealthPermissionCategory.Type int permissionCategory) {
        return Objects.requireNonNull(
                mPermissionCategoryToReadPermissionMap.get(permissionCategory),
                "Read permissions not found for permission category:" + permissionCategory);
    }

    /** @hide */
    public String getHealthWritePermission(@HealthPermissionCategory.Type int permissionCategory) {
        return Objects.requireNonNull(
                mPermissionCategoryToWritePermissionMap.get(permissionCategory),
                "Write permissions not found for permission category:" + permissionCategory);
    }

    /**
     * @return {@link HealthDataCategory} for a WRITE {@code permissionName}. -1 if permission
     *     category for {@code permissionName} is not found (or if {@code permissionName} is READ)
     * @hide
     */
    @HealthDataCategory.Type
    public int getHealthDataCategoryForWritePermission(@Nullable String permissionName) {
        return mWritePermissionToDataCategoryMap.getOrDefault(permissionName, DEFAULT_INT);
    }

    /**
     * @return {@link HealthDataCategory} for a READ {@code permissionName}. -1 if permission
     *     category for {@code permissionName} is not found (or if {@code permissionName} is READ)
     * @hide
     */
    @HealthPermissionCategory.Type
    public int getHealthPermissionCategoryForReadPermission(@Nullable String permissionName) {
        return mReadPermissionToPermissionCategoryMap.getOrDefault(permissionName, DEFAULT_INT);
    }

    /**
     * @return a write permission for given read permission or null if there is no corresponding
     *     write permission.
     *     <p>Note: This method contains a special case for {@code READ_EXERCISE_ROUTES} which is
     *     mapped to {@code WRITE_EXERCISE_ROUTE}.
     * @hide
     */
    @Nullable
    public String getWritePermissionForReadPermission(String readPermission) {
        if (READ_EXERCISE_ROUTES.equals(readPermission)) {
            return WRITE_EXERCISE_ROUTE;
        }
        int permissionCategory = getHealthPermissionCategoryForReadPermission(readPermission);
        if (permissionCategory == DEFAULT_INT) {
            return null;
        }

        return getHealthWritePermission(permissionCategory);
    }

    /**
     * @return an array of write permission for given data category.
     * @hide
     */
    public String[] getWriteHealthPermissionsFor(@HealthDataCategory.Type int dataCategory) {
        return mDataCategoryToWritePermissionsMap.getOrDefault(dataCategory, new String[] {});
    }

    /**
     * Returns a mapping from {@link RecordTypeIdentifier} to corresponding {@link RecordInternal}.
     */
    public Map<Integer, Class<? extends RecordInternal<?>>> getRecordIdToInternalRecordClassMap() {
        return mRecordIdToInternalRecordClassMap;
    }

    /** Returns a mapping from {@link RecordTypeIdentifier} to corresponding {@link Record}. */
    public Map<Integer, Class<? extends Record>> getRecordIdToExternalRecordClassMap() {
        return mRecordIdToRecordClassMap;
    }

    /** Returns record type id for give record class. */
    @RecordTypeIdentifier.RecordType
    public int getRecordType(Class<? extends Record> recordClass) {
        return Objects.requireNonNull(mRecordClassToRecordIdMap.get(recordClass));
    }

    /** Checks whether the given {@code recordClass} can be mapped. */
    public boolean hasRecordType(Class<? extends Record> recordClass) {
        return mRecordClassToRecordIdMap.containsKey(recordClass);
    }

    /**
     * Returns a set of {@link HealthPermissionCategory} for the input {@link
     * RecordTypeIdentifier.RecordType}.
     */
    @HealthPermissionCategory.Type
    public Set<@HealthPermissionCategory.Type Integer> getHealthPermissionCategoriesForRecordType(
            @RecordTypeIdentifier.RecordType int recordType) {
        return Objects.requireNonNull(
                mRecordIdToPermissionCategoriesMap.get(recordType),
                "Unsupported record type: " + recordType);
    }

    /** Returns {@link HealthDataCategory} for the input {@link RecordTypeIdentifier.RecordType}. */
    @HealthDataCategory.Type
    public int getRecordCategoryForRecordType(@RecordTypeIdentifier.RecordType int recordType) {
        return Objects.requireNonNull(
                        mRecordIdToDescriptorMap.get(recordType),
                        "Unsupported record type: " + recordType)
                .getDataCategory();
    }

    /**
     * Returns {@link RecordTypeSensitivity} for the input {@link RecordTypeIdentifier.RecordType}.
     *
     * @hide
     */
    @RecordTypeSensitivity.Sensitivity
    public int getSensitivityForRecordType(@RecordTypeIdentifier.RecordType int recordType) {
        return Objects.requireNonNull(
                        mRecordIdToDescriptorMap.get(recordType),
                        "Unsupported record type: " + recordType)
                .getRecordTypeSensitivity();
    }

    /** Returns a set of all supported data categories. */
    public Set<Integer> getAllHealthDataCategories() {
        return mHealthDataCategories;
    }

    private static ArrayMap<Integer, String[]> getDataCategoryToWritePermissionsMap(
            List<DataTypeDescriptor> descriptors) {
        Map<Integer, Set<String>> map =
                descriptors.stream()
                        .collect(
                                groupingBy(
                                        DataTypeDescriptor::getDataCategory,
                                        flatMapping(
                                                HealthConnectMappings::getWritePermissionStream,
                                                toSet())));

        ArrayMap<Integer, String[]> result = new ArrayMap<>();
        map.forEach((k, v) -> result.put(k, v.toArray(new String[0])));
        return result;
    }

    private static Stream<String> getWritePermissionStream(DataTypeDescriptor descriptor) {
        return descriptor.getPermissionCategories().stream()
                .map(PermissionCategory::writePermission);
    }

    private static ArrayMap<Integer, Set<Integer>> createRecordIdToPermissionCategoriesMap(
            List<DataTypeDescriptor> descriptors) {
        return descriptors.stream()
                .collect(
                        groupingBy(
                                DataTypeDescriptor::getRecordTypeIdentifier,
                                ArrayMap::new,
                                flatMapping(
                                        descriptor ->
                                                descriptor.getPermissionCategories().stream()
                                                        .map(
                                                                PermissionCategory
                                                                        ::permissionCategoryId),
                                        toSet())));
    }

    private static ArrayMap<Integer, String> createPermissionCategoryToReadPermissionMap(
            List<DataTypeDescriptor> descriptors) {
        return descriptors.stream()
                .flatMap(descriptor -> descriptor.getPermissionCategories().stream())
                .collect(
                        toMap(
                                PermissionCategory::permissionCategoryId,
                                PermissionCategory::readPermission,
                                (permission1, permission2) -> permission1,
                                ArrayMap::new));
    }

    private static ArrayMap<Integer, String> createPermissionCategoryToWritePermissionMap(
            List<DataTypeDescriptor> descriptors) {
        return descriptors.stream()
                .flatMap(descriptor -> descriptor.getPermissionCategories().stream())
                .collect(
                        toMap(
                                PermissionCategory::permissionCategoryId,
                                PermissionCategory::writePermission,
                                (permission1, permission2) -> permission1,
                                ArrayMap::new));
    }

    private static ArrayMap<String, Integer> createReadPermissionToPermissionCategoryMap(
            List<DataTypeDescriptor> descriptors) {
        return descriptors.stream()
                .flatMap(descriptor -> descriptor.getPermissionCategories().stream())
                .collect(
                        toMap(
                                PermissionCategory::readPermission,
                                PermissionCategory::permissionCategoryId,
                                (permission1, permission2) -> permission1,
                                ArrayMap::new));
    }

    private static ArrayMap<String, Integer> createWritePermissionToDataCategoryMap(
            List<DataTypeDescriptor> descriptors) {
        ArrayMap<String, Integer> map = new ArrayMap<>();
        for (DataTypeDescriptor descriptor : descriptors) {
            for (PermissionCategory mapping : descriptor.getPermissionCategories()) {
                map.put(mapping.writePermission(), descriptor.getDataCategory());
            }
        }
        return map;
    }

    private static <T, K, V> ArrayMap<K, V> toArrayMap(
            Collection<T> collection, Function<T, K> keyFunc, Function<T, V> valueFunc) {
        ArrayMap<K, V> map = new ArrayMap<>(collection.size());

        for (var item : collection) {
            K key = keyFunc.apply(item);
            V value = valueFunc.apply(item);
            map.put(key, value);
        }

        return map;
    }

    private static <T, R> ArraySet<R> toArraySet(Collection<T> collection, Function<T, R> mapFunc) {
        ArraySet<R> set = new ArraySet<>(collection.size());

        for (var item : collection) {
            set.add(mapFunc.apply(item));
        }

        return set;
    }
}
