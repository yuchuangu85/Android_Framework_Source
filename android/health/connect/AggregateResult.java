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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.internal.PackageNameMasker;
import android.os.Parcel;
import android.util.ArraySet;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A class to represent the results of {@link HealthConnectManager} aggregate APIs
 *
 * @param <T> The type of the aggregated result (e.g., Long, Duration)
 * @hide
 */
public final class AggregateResult<T> implements PackageNameMasker<AggregateResult<T>> {

    private final T mResult;
    @Nullable private final ZoneOffset mZoneOffset;
    private final Set<DataOrigin> mDataOrigins;

    public AggregateResult(T result, @Nullable ZoneOffset zoneOffset, Set<DataOrigin> dataOrigins) {
        mResult = result;
        mZoneOffset = zoneOffset;
        mDataOrigins = dataOrigins;
    }

    public void putToParcel(@NonNull Parcel parcel) {
        if (mResult instanceof Long) {
            parcel.writeLong((Long) mResult);
        } else if (mResult instanceof Double) {
            parcel.writeDouble((Double) mResult);
        }
    }

    /**
     * @return {@link ZoneOffset} for the underlying record, null if aggregation was derived from
     *     multiple records
     */
    @Nullable
    public ZoneOffset getZoneOffset() {
        return mZoneOffset;
    }

    /** Returns set of {@link DataOrigin} that contributed to the aggregation result */
    @NonNull
    public Set<DataOrigin> getDataOrigins() {
        return mDataOrigins;
    }

    /**
     * @return an Object representing the result of an aggregation.
     */
    @NonNull
    T getResult() {
        return mResult;
    }

    /** Returns a Set of {@link DataOrigin} that contributed to the aggregation result. */
    public static Set<DataOrigin> convertDataOrigins(@NonNull List<String> packageNameList) {
        Set<DataOrigin> result = new ArraySet<>();

        for (String packageName : packageNameList) {
            result.add(new DataOrigin.Builder().setPackageName(packageName).build());
        }
        return result;
    }

    @NonNull
    @Override
    public AggregateResult<T> toMasked(@NonNull Function<String, String> packageMasker) {
        Set<DataOrigin> maskedOrigins =
                mDataOrigins.stream()
                        .map(
                                origin ->
                                        new DataOrigin.Builder()
                                                .setPackageName(
                                                        packageMasker.apply(
                                                                origin.getPackageName()))
                                                .build())
                        .collect(Collectors.toSet());

        return new AggregateResult<>(mResult, mZoneOffset, maskedOrigins);
    }
}
