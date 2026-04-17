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
import static com.android.healthfitness.flags.Flags.FLAG_MATCHMAKING;

import static java.util.Objects.hash;
import static java.util.Objects.requireNonNull;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.Record;
import android.health.connect.internal.datatypes.utils.HealthConnectMappings;
import android.os.OutcomeReceiver;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.healthfitness.flags.Flags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Represents a request to determine if there are matching data sources for a given set of record
 * types. This request is used with {@link
 * HealthConnectManager#isMatchmakingPossible(MatchmakingRequest, Executor, OutcomeReceiver)} and
 * {@link HealthConnectManager#createMatchmakingIntent(MatchmakingRequest)}.
 */
@FlaggedApi(FLAG_MATCHMAKING)
public final class MatchmakingRequest implements Parcelable {
    @NonNull private final Set<Class<? extends Record>> mRecordTypes;
    @Nullable private final String mCallingPackageName;
    @NonNull private final Set<DataOrigin> mIncludedDataSources;
    @NonNull private final Set<DataOrigin> mExcludedDataSources;

    /**
     * Private constructor to create a {@link MatchmakingRequest} instance. Use the {@link Builder}
     * to construct new instances.
     *
     * @param recordTypes The set of record types for which to find matching data sources.
     * @param callingPackageName The package name that initiated matchmaking. Can be null if not
     *     specified. This parameter is only intended to be set by the Health Connect controller
     *     APK.
     * @param includedDataSources The {@link Set} of {@link DataOrigin}s to include in the
     *     matchmaking.
     * @param excludedDataSources The {@link Set} of {@link DataOrigin}s to exclude from the
     *     matchmaking.
     */
    private MatchmakingRequest(
            @NonNull Set<Class<? extends Record>> recordTypes,
            @Nullable String callingPackageName,
            @NonNull Set<DataOrigin> includedDataSources,
            @NonNull Set<DataOrigin> excludedDataSources) {
        mRecordTypes = Set.copyOf(recordTypes);
        mCallingPackageName = callingPackageName;
        mIncludedDataSources = Set.copyOf(includedDataSources);
        mExcludedDataSources = Set.copyOf(excludedDataSources);
    }

    /**
     * Private constructor to reconstruct a {@link MatchmakingRequest} from a {@link Parcel}.
     *
     * @param in The Parcel from which to read the object data.
     */
    private MatchmakingRequest(@NonNull Parcel in) {
        requireNonNull(in);
        int[] recordTypeIds = requireNonNull(in.createIntArray());
        mRecordTypes =
                Arrays.stream(recordTypeIds)
                        .mapToObj(
                                recordTypeId ->
                                        HealthConnectMappings.getInstance()
                                                .getRecordIdToExternalRecordClassMap()
                                                .get(recordTypeId))
                        .collect(Collectors.toUnmodifiableSet());
        mCallingPackageName = in.readString();

        if (Flags.deviceDataProvidersApi()) {
            List<String> includePackages = new ArrayList<>();
            in.readStringList(includePackages);
            mIncludedDataSources =
                    includePackages.stream()
                            .map(p -> new DataOrigin.Builder().setPackageName(p).build())
                            .collect(Collectors.toUnmodifiableSet());

            List<String> excludePackages = new ArrayList<>();
            in.readStringList(excludePackages);
            mExcludedDataSources =
                    excludePackages.stream()
                            .map(p -> new DataOrigin.Builder().setPackageName(p).build())
                            .collect(Collectors.toUnmodifiableSet());
        } else {
            mIncludedDataSources = Set.of();
            mExcludedDataSources = Set.of();
        }
    }

    @NonNull
    public static final Creator<MatchmakingRequest> CREATOR =
            new Creator<>() {
                @Override
                public MatchmakingRequest createFromParcel(Parcel in) {
                    return new MatchmakingRequest(in);
                }

                @Override
                public MatchmakingRequest[] newArray(int size) {
                    return new MatchmakingRequest[size];
                }
            };

    /** Returns the set of record types for which matching data sources are being requested. */
    @NonNull
    public Set<Class<? extends Record>> getRecordTypes() {
        return mRecordTypes;
    }

    /** @hide */
    @Nullable
    public String getCallingPackageName() {
        return mCallingPackageName;
    }

    /** Returns the {@link Set} of {@link DataOrigin}s to include in the matchmaking. */
    @NonNull
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public Set<DataOrigin> getIncludedDataSources() {
        return mIncludedDataSources;
    }

    /** Returns the {@link Set} of {@link DataOrigin}s to exclude from the matchmaking. */
    @NonNull
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public Set<DataOrigin> getExcludedDataSources() {
        return mExcludedDataSources;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeIntArray(
                mRecordTypes.stream()
                        .mapToInt(
                                recordTypeClass ->
                                        HealthConnectMappings.getInstance()
                                                .getRecordType(recordTypeClass))
                        .toArray());
        dest.writeString(mCallingPackageName);

        if (Flags.deviceDataProvidersApi()) {
            dest.writeStringList(
                    mIncludedDataSources.stream().map(DataOrigin::getPackageName).toList());
            dest.writeStringList(
                    mExcludedDataSources.stream().map(DataOrigin::getPackageName).toList());
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MatchmakingRequest that)) return false;
        return mRecordTypes.equals(that.mRecordTypes)
                && Objects.equals(mCallingPackageName, that.mCallingPackageName)
                && mIncludedDataSources.equals(that.mIncludedDataSources)
                && mExcludedDataSources.equals(that.mExcludedDataSources);
    }

    @Override
    public int hashCode() {
        return hash(mRecordTypes, mCallingPackageName, mIncludedDataSources, mExcludedDataSources);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.getClass().getSimpleName()).append("{");
        sb.append("recordTypes=").append(mRecordTypes);
        sb.append(",callingPackageName=").append(mCallingPackageName);
        if (Flags.deviceDataProvidersApi()) {
            sb.append(",includedDataSources=")
                    .append(convertDataOriginSetToString(mIncludedDataSources));
            sb.append(",excludedDataSources=")
                    .append(convertDataOriginSetToString(mExcludedDataSources));
            sb.append("}");
        }
        return sb.toString();
    }

    private String convertDataOriginSetToString(Set<DataOrigin> dataOriginSet) {
        if (dataOriginSet == null || dataOriginSet.isEmpty()) {
            return "[]";
        }
        return dataOriginSet.stream()
                .map(DataOrigin::getPackageName)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /** @hide */
    @NonNull
    public MatchmakingRequest toUnmasked(@NonNull Function<String, String> packageUnmasker) {
        return new MatchmakingRequest(
                mRecordTypes,
                mCallingPackageName,
                mIncludedDataSources.stream()
                        .map(
                                p ->
                                        new DataOrigin.Builder()
                                                .setPackageName(
                                                        packageUnmasker.apply(p.getPackageName()))
                                                .build())
                        .collect(Collectors.toUnmodifiableSet()),
                mExcludedDataSources.stream()
                        .map(
                                p ->
                                        new DataOrigin.Builder()
                                                .setPackageName(
                                                        packageUnmasker.apply(p.getPackageName()))
                                                .build())
                        .collect(Collectors.toUnmodifiableSet()));
    }

    /**
     * Builder class for {@link MatchmakingRequest}.
     *
     * <p>By default, the builder initializes with an empty set of record types, include and exclude
     * data sources.
     */
    public static final class Builder {
        private final Set<Class<? extends Record>> mRecordTypes = new HashSet<>();
        @Nullable private String mCallingPackageName;
        private Set<DataOrigin> mIncludedDataSources = Set.of();
        private Set<DataOrigin> mExcludedDataSources = Set.of();

        /**
         * Adds a record type to the request.
         *
         * @param recordType The record type to add.
         * @return This builder.
         */
        @NonNull
        public Builder addRecordType(@NonNull Class<? extends Record> recordType) {
            mRecordTypes.add(requireNonNull(recordType));
            return this;
        }

        /**
         * Adds a list of record types to the request.
         *
         * @param recordTypes The list of record types to add.
         * @return This builder.
         */
        @NonNull
        public Builder addRecordTypes(@NonNull Set<Class<? extends Record>> recordTypes) {
            mRecordTypes.addAll(requireNonNull(recordTypes));
            return this;
        }

        /**
         * Sets the calling package name for the request. This is only to be set by Health Connect
         * controller APK(s).
         *
         * @param callingPackageName The package name to set.
         * @return This builder.
         * @hide
         */
        @NonNull
        public Builder setCallingPackageName(@NonNull String callingPackageName) {
            mCallingPackageName = requireNonNull(callingPackageName);
            return this;
        }

        /**
         * Sets the {@link DataOrigin}s to be considered by matchmaking. Only data sources in this
         * set will be considered; data sources not in this set will be excluded. Providing an empty
         * set means no specific data sources are included, effectively allowing all data sources to
         * be considered (unless excluded by {@link #setExcludedDataSources(Set)}.
         *
         * <p>By default, if neither {@link #setIncludedDataSources} nor {@link
         * #setExcludedDataSources} are called, all available data sources will be considered.
         *
         * @param dataOrigins The data origins to include. An empty set means no specific data
         *     sources are included.
         * @return This builder.
         * @throws IllegalStateException if {@link #setExcludedDataSources} has been called with a
         *     non-empty set.
         */
        @NonNull
        @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
        public Builder setIncludedDataSources(@NonNull Set<DataOrigin> dataOrigins) {
            if (!mExcludedDataSources.isEmpty() && !dataOrigins.isEmpty()) {
                throw new IllegalStateException("excludeDataSources already set for this request");
            }

            mIncludedDataSources = Set.copyOf(requireNonNull(dataOrigins));
            return this;
        }

        /**
         * Sets the {@link DataOrigin}s to be excluded from matchmaking. Data sources in this set
         * will not be considered. Providing an empty set means no specific data sources are
         * excluded, effectively allowing all data sources to be considered (unless included by
         * {@link #setIncludedDataSources(Set)}).
         *
         * <p>By default, if neither {@link #setIncludedDataSources} nor {@link
         * #setExcludedDataSources} are called, all available data sources will be considered.
         *
         * @param dataOrigins The data origins to exclude. An empty set means no specific data
         *     sources are excluded.
         * @return This builder.
         * @throws IllegalStateException if {@link #setIncludedDataSources} has been called with a
         *     non-empty set.
         */
        @NonNull
        @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
        public Builder setExcludedDataSources(@NonNull Set<DataOrigin> dataOrigins) {
            if (!mIncludedDataSources.isEmpty() && !dataOrigins.isEmpty()) {
                throw new IllegalStateException("includeDataSources already set for this request");
            }

            mExcludedDataSources = Set.copyOf(requireNonNull(dataOrigins));
            return this;
        }

        /**
         * Builds the {@link MatchmakingRequest} instance.
         *
         * <p>By default, {@code MatchmakingRequest.Builder().build()} creates a request with an
         * empty set of record types, included and excluded data sources. See description at {@link
         * HealthConnectManager#createMatchmakingIntent(MatchmakingRequest)}.
         *
         * @return A new instance of {@link MatchmakingRequest}.
         */
        @NonNull
        public MatchmakingRequest build() {
            if (!Flags.deviceDataProvidersApi()
                    && !(mIncludedDataSources.isEmpty() && mExcludedDataSources.isEmpty())) {
                throw new UnsupportedOperationException(
                        "Setting includedDataSources and excludedDataSources is not supported");
            }
            if (!mIncludedDataSources.isEmpty() && !mExcludedDataSources.isEmpty()) {
                throw new IllegalArgumentException(
                        "Cannot set both includeDataSources and excludeDataSources");
            }

            return new MatchmakingRequest(
                    mRecordTypes, mCallingPackageName, mIncludedDataSources, mExcludedDataSources);
        }
    }
}
