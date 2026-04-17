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

package android.health.connect;

import static com.android.healthfitness.flags.Flags.FLAG_DEVICE_DATA_PROVIDERS_API;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.health.connect.datatypes.Record;
import android.health.connect.internal.datatypes.utils.HealthConnectMappings;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Represents the set of record types a device data source can provide.
 *
 * @see HealthConnectManager#getDeviceDataSourceCapabilities
 */
@FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
public final class DeviceDataSourceCapabilities {
    private final Set<Class<? extends Record>> mRecordTypes;

    /** @hide */
    public DeviceDataSourceCapabilities(
            @NonNull android.health.connect.aidl.DeviceDataSourceCapabilities aidlCapabilities) {
        Objects.requireNonNull(aidlCapabilities);
        if (aidlCapabilities.recordTypeIds == null) {
            mRecordTypes = Set.of();
        } else {
            HealthConnectMappings mappings = HealthConnectMappings.getInstance();
            mRecordTypes =
                    Arrays.stream(aidlCapabilities.recordTypeIds)
                            .mapToObj(id -> mappings.getRecordIdToExternalRecordClassMap().get(id))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toUnmodifiableSet());
        }
    }

    /** Returns the set of record types that can be provided. */
    @NonNull
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public Set<Class<? extends Record>> getRecordTypes() {
        return mRecordTypes;
    }
}
