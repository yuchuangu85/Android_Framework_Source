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

import static android.health.connect.Constants.DEFAULT_LONG;
import static android.health.connect.Constants.DEFAULT_PAGE_SIZE;
import static android.health.connect.Constants.MAXIMUM_PAGE_SIZE;
import static android.health.connect.Constants.MINIMUM_PAGE_SIZE;

import static com.android.healthfitness.flags.Flags.FLAG_DEVICE_DATA_PROVIDERS_API;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.health.connect.aidl.ReadRecordsRequestParcel;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.Record;
import android.os.OutcomeReceiver;
import android.util.ArraySet;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Class to represent a request based on time range and data origin filters for {@link
 * HealthConnectManager#readRecords(ReadRecordsRequest, Executor, OutcomeReceiver)}
 *
 * @param <T> the type of the Record for the request
 */
public final class ReadRecordsRequestUsingFilters<T extends Record> extends ReadRecordsRequest<T> {
    @Nullable private final TimeRangeFilter mTimeRangeFilter;
    private final Set<DataOrigin> mDataOrigins;
    private final int mPageSize;
    private final long mPageToken;
    private final boolean mAscending;
    @Nullable private final String mDeviceId;

    /**
     * @see Builder
     */
    private ReadRecordsRequestUsingFilters(
            @Nullable TimeRangeFilter timeRangeFilter,
            @NonNull Class<T> recordType,
            @NonNull Set<DataOrigin> dataOrigins,
            int pageSize,
            long pageToken,
            boolean ascending,
            @Nullable String deviceId) {
        super(recordType);
        Objects.requireNonNull(dataOrigins);
        mTimeRangeFilter = timeRangeFilter;
        mDataOrigins = dataOrigins;
        mPageSize = pageSize;
        mAscending = PageTokenWrapper.from(pageToken, ascending).isAscending();
        mPageToken = pageToken;
        mDeviceId = deviceId;
    }

    /** Returns time range b/w which the read operation is to be performed */
    @Nullable
    public TimeRangeFilter getTimeRangeFilter() {
        return mTimeRangeFilter;
    }

    /**
     * Returns the set of {@link DataOrigin data origins} to be read, or empty list for no filter
     */
    @NonNull
    public Set<DataOrigin> getDataOrigins() {
        return mDataOrigins;
    }

    /** Returns maximum number of records to be returned by the read operation */
    @IntRange(from = 1, to = 5000)
    public int getPageSize() {
        return mPageSize;
    }

    /** Returns page token to read the current page of the result. -1 if none available */
    public long getPageToken() {
        return mPageToken;
    }

    /** Returns ordering of results to be returned */
    public boolean isAscending() {
        return mAscending;
    }

    /**
     * Returns the device id to filter the request with, or {@code null} for no filter.
     *
     * @hide
     */
    @Nullable
    @SystemApi
    @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
    public String getDeviceId() {
        return mDeviceId;
    }

    /**
     * Returns an object of ReadRecordsRequestParcel to carry read request
     *
     * @hide
     */
    @NonNull
    public ReadRecordsRequestParcel toReadRecordsRequestParcel() {
        return new ReadRecordsRequestParcel(this);
    }

    /** Builder class for {@link ReadRecordsRequestUsingFilters} */
    public static final class Builder<T extends Record> {
        private final Class<T> mRecordType;
        private final Set<DataOrigin> mDataOrigins = new ArraySet<>();
        @Nullable private TimeRangeFilter mTimeRangeFilter;
        private int mPageSize = DEFAULT_PAGE_SIZE;
        private long mPageToken = DEFAULT_LONG;
        private boolean mAscending = true;
        private boolean mIsOrderingSet = false;
        @Nullable private String mDeviceId;

        /**
         * @param recordType Class object of {@link Record} type that needs to be read
         */
        public Builder(@NonNull Class<T> recordType) {
            Objects.requireNonNull(recordType);

            mRecordType = recordType;
        }

        /**
         * Sets the data origin filter based on which the read operation is to be performed.
         *
         * @param dataOrigin Adds {@link DataOrigin} for which to read records.
         *     <p>If no {@link DataOrigin} is added then records by all {@link DataOrigin}s will be
         *     read
         */
        @NonNull
        public Builder<T> addDataOrigins(@NonNull DataOrigin dataOrigin) {
            Objects.requireNonNull(dataOrigin);
            mDataOrigins.add(dataOrigin);

            return this;
        }

        /**
         * Sets time range b/w which the read operation is to be performed
         *
         * @param timeRangeFilter Time range b/w which the read operation is to be performed.
         *     <p>If not time range filter is present all the records will be read without any time
         *     constraints.
         */
        @NonNull
        public Builder<T> setTimeRangeFilter(@Nullable TimeRangeFilter timeRangeFilter) {
            mTimeRangeFilter = timeRangeFilter;
            return this;
        }

        /**
         * Sets maximum number of records to be returned by the read operation
         *
         * @param pageSize number of records to be returned by the read operation.
         *     <p>This sets to limit number of rows returned by a read. If not set default is 1000
         *     and maximum of 5000 records can be sent.
         */
        @NonNull
        public Builder<T> setPageSize(@IntRange(from = 1, to = 5000) int pageSize) {
            if (pageSize < MINIMUM_PAGE_SIZE || pageSize > MAXIMUM_PAGE_SIZE) {
                throw new IllegalArgumentException(
                        "Valid pageSize range is "
                                + MINIMUM_PAGE_SIZE
                                + " - "
                                + MAXIMUM_PAGE_SIZE
                                + ", requested "
                                + pageSize);
            }
            mPageSize = pageSize;
            return this;
        }

        /**
         * Sets page token to read the requested page of the result.
         *
         * @param pageToken to read the requested page of the result. -1 if none available
         */
        @NonNull
        public Builder<T> setPageToken(long pageToken) {
            mPageToken = pageToken;
            return this;
        }

        /**
         * Sets ordering of results to be returned based on start time. Ordering cannot be set along
         * with page token for subsequent requests. IllegalState exception is thrown when ordering
         * is set along with the page token.
         *
         * @param ascending specifies sorting order of results, if set to true records are sorted on
         *     start time in ascending fashion, else if set to false then in descending.
         */
        @NonNull
        public Builder<T> setAscending(boolean ascending) {
            mAscending = ascending;
            mIsOrderingSet = true;
            return this;
        }

        /**
         * Sets the device id filter based on which the read operation is to be performed.
         *
         * <p>A device id cannot be set along with data origins.
         *
         * @param deviceId the id of the device for which to read records.
         * @throws IllegalStateException if a device id is set along with data origins.
         * @hide
         */
        @NonNull
        @SystemApi
        @FlaggedApi(FLAG_DEVICE_DATA_PROVIDERS_API)
        public Builder<T> setDeviceId(@NonNull String deviceId) {
            mDeviceId = deviceId;
            return this;
        }

        /**
         * Returns an Object of {@link ReadRecordsRequestUsingFilters}
         *
         * <p>For subsequent read requests, {@link ReadRecordsRequestUsingFilters} does not allow
         * both pageToken and sort order to be set together.
         *
         * <p>If pageToken is set then records will be sorted in same order as the previous result
         *
         * <p>If both pageToken and sortOrder are not set then by default records will be sorted by
         * start time in ascending order.
         *
         * @throws IllegalStateException if both pageToken and sort order is set.
         */
        @NonNull
        public ReadRecordsRequestUsingFilters<T> build() {
            if (mPageToken != DEFAULT_LONG && mIsOrderingSet) {
                throw new IllegalStateException("Cannot set both pageToken and sort order");
            }
            if (mDeviceId != null && !mDataOrigins.isEmpty()) {
                throw new IllegalStateException("Cannot set both device id and data origins");
            }

            return new ReadRecordsRequestUsingFilters<>(
                    mTimeRangeFilter,
                    mRecordType,
                    mDataOrigins,
                    mPageSize,
                    mPageToken,
                    mAscending,
                    mDeviceId);
        }
    }
}
