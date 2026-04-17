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

import static com.android.healthfitness.flags.Flags.FLAG_PHR_CHANGE_LOGS;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.health.connect.HealthConnectManager;
import android.health.connect.MedicalResourceId;
import android.health.connect.aidl.DeletedLogsParcel;
import android.health.connect.aidl.DeletedMedicalResourcesParcel;
import android.health.connect.aidl.MedicalResourceListParcel;
import android.health.connect.aidl.RecordsParcel;
import android.health.connect.datatypes.MedicalResource;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.health.connect.internal.datatypes.RecordInternal;
import android.health.connect.internal.datatypes.utils.InternalExternalRecordConverter;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.healthfitness.flags.AconfigFlagHelper;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Response class for {@link HealthConnectManager#getChangeLogs} This is the response to clients
 * fetching changes.
 */
public final class ChangeLogsResponse implements Parcelable {
    private final List<Record> mUpsertedRecords;
    private final List<DeletedLog> mDeletedLogs;
    private final List<MedicalResource> mUpsertedMedicalResources;
    private final List<DeletedMedicalResource> mDeletedMedicalResources;
    private final String mNextChangesToken;
    private final boolean mHasMorePages;

    /**
     * Response for {@link HealthConnectManager#getChangeLogs}.
     *
     * @hide
     * @deprecated Use {@link #ChangeLogsResponse(List, List, List, List, String, boolean)}.
     */
    @Deprecated
    public ChangeLogsResponse(
            @NonNull RecordsParcel upsertedRecords,
            @NonNull List<DeletedLog> deletedLogs,
            @NonNull String nextChangesToken,
            boolean hasMorePages) {
        mUpsertedRecords =
                InternalExternalRecordConverter.getInstance()
                        .getExternalRecords(Objects.requireNonNull(upsertedRecords).getRecords());
        mDeletedLogs = Objects.requireNonNull(deletedLogs);
        mUpsertedMedicalResources = List.of();
        mDeletedMedicalResources = List.of();
        mNextChangesToken = Objects.requireNonNull(nextChangesToken);
        mHasMorePages = hasMorePages;
    }

    /**
     * Response for {@link HealthConnectManager#getChangeLogs}.
     *
     * @hide
     */
    public ChangeLogsResponse(
            @NonNull List<Record> upsertedRecords,
            @NonNull List<DeletedLog> deletedLogs,
            @NonNull List<MedicalResource> upsertedMedicalResources,
            @NonNull List<DeletedMedicalResource> deletedMedicalResources,
            @NonNull String nextChangesToken,
            boolean hasMorePages) {
        mUpsertedRecords = Objects.requireNonNull(upsertedRecords);
        mDeletedLogs = Objects.requireNonNull(deletedLogs);
        mUpsertedMedicalResources = Objects.requireNonNull(upsertedMedicalResources);
        mDeletedMedicalResources = Objects.requireNonNull(deletedMedicalResources);
        mNextChangesToken = Objects.requireNonNull(nextChangesToken);
        mHasMorePages = hasMorePages;
    }

    private ChangeLogsResponse(Parcel in) {
        mUpsertedRecords =
                InternalExternalRecordConverter.getInstance()
                        .getExternalRecords(
                                in.readParcelable(
                                                RecordsParcel.class.getClassLoader(),
                                                RecordsParcel.class)
                                        .getRecords());
        mDeletedLogs =
                in.readParcelable(DeletedLogsParcel.class.getClassLoader(), DeletedLogsParcel.class)
                        .getDeletedLogs();
        mNextChangesToken = in.readString();
        mHasMorePages = in.readBoolean();
        mUpsertedMedicalResources =
                AconfigFlagHelper.isPhrChangeLogsEnabled()
                        ? in.readParcelable(
                                        MedicalResourceListParcel.class.getClassLoader(),
                                        MedicalResourceListParcel.class)
                                .getMedicalResources()
                        : List.of();
        mDeletedMedicalResources =
                AconfigFlagHelper.isPhrChangeLogsEnabled()
                        ? in.readParcelable(
                                        DeletedMedicalResourcesParcel.class.getClassLoader(),
                                        DeletedMedicalResourcesParcel.class)
                                .getDeletedMedicalResources()
                        : List.of();
    }

    @NonNull
    public static final Creator<ChangeLogsResponse> CREATOR =
            new Creator<>() {
                @Override
                public ChangeLogsResponse createFromParcel(Parcel in) {
                    return new ChangeLogsResponse(in);
                }

                @Override
                public ChangeLogsResponse[] newArray(int size) {
                    return new ChangeLogsResponse[size];
                }
            };

    /**
     * Returns records that have been updated or inserted post the time when the given token was
     * generated.
     *
     * <p>Clients can use the last modified time of the record to check when the record was
     * modified.
     */
    @NonNull
    public List<Record> getUpsertedRecords() {
        return mUpsertedRecords;
    }

    /**
     * Returns delete logs for records that have been deleted post the time when the token was
     * retrieved.
     *
     * <p>This contains record ids of deleted records and the timestamps when the records were
     * deleted.
     */
    @NonNull
    public List<DeletedLog> getDeletedLogs() {
        return mDeletedLogs;
    }

    /**
     * Returns medical resources that have been updated or inserted post the time when the given
     * token was generated.
     */
    @NonNull
    @FlaggedApi(FLAG_PHR_CHANGE_LOGS)
    public List<MedicalResource> getUpsertedMedicalResources() {
        return mUpsertedMedicalResources;
    }

    /**
     * Returns delete logs for medical resources that have been deleted post the time when the token
     * was retrieved.
     *
     * <p>This contains ids of deleted medical resources and the timestamps when the resources were
     * deleted.
     */
    @NonNull
    @FlaggedApi(FLAG_PHR_CHANGE_LOGS)
    public List<DeletedMedicalResource> getDeletedMedicalResources() {
        return mDeletedMedicalResources;
    }

    /** Returns token for future reads using {@link HealthConnectManager#getChangeLogs}. */
    @NonNull
    public String getNextChangesToken() {
        return mNextChangesToken;
    }

    /** Returns whether there are more pages available for read. */
    public boolean hasMorePages() {
        return mHasMorePages;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        List<RecordInternal<?>> recordInternals =
                mUpsertedRecords.stream()
                        .map(Record::toRecordInternal)
                        .collect(Collectors.toList());
        dest.writeParcelable(new RecordsParcel(recordInternals), 0);
        dest.writeParcelable(new DeletedLogsParcel(mDeletedLogs), 0);
        dest.writeString(mNextChangesToken);
        dest.writeBoolean(mHasMorePages);
        if (AconfigFlagHelper.isPhrChangeLogsEnabled()) {
            dest.writeParcelable(new MedicalResourceListParcel(mUpsertedMedicalResources), 0);
            dest.writeParcelable(new DeletedMedicalResourcesParcel(mDeletedMedicalResources), 0);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ChangeLogsResponse that)) return false;
        return mHasMorePages == that.mHasMorePages
                && Objects.equals(mUpsertedRecords, that.mUpsertedRecords)
                && Objects.equals(mDeletedLogs, that.mDeletedLogs)
                && Objects.equals(mUpsertedMedicalResources, that.mUpsertedMedicalResources)
                && Objects.equals(mDeletedMedicalResources, that.mDeletedMedicalResources)
                && Objects.equals(mNextChangesToken, that.mNextChangesToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mUpsertedRecords,
                mDeletedLogs,
                mUpsertedMedicalResources,
                mDeletedMedicalResources,
                mNextChangesToken,
                mHasMorePages);
    }

    /**
     * A change log holds the {@link Metadata#getId()} of a deleted Record. For privacy, only unique
     * identifiers of deleted records are returned.
     *
     * <p>Clients holding copies of data from Health Connect should keep a copy of these unique
     * identifiers along with their contents. When receiving a {@link DeletedLog} in {@link
     * ChangeLogsResponse}, use the identifiers to delete copy of the data.
     */
    public static final class DeletedLog {
        private final String mDeletedRecordId;
        private final Instant mDeletedTime;

        /**
         * @deprecated Use {@link #DeletedLog(String, Instant)}.
         */
        @FlaggedApi(FLAG_PHR_CHANGE_LOGS)
        @Deprecated
        public DeletedLog(@NonNull String deletedRecordId, long deletedTime) {
            Objects.requireNonNull(deletedRecordId);
            mDeletedRecordId = deletedRecordId;
            mDeletedTime = Instant.ofEpochMilli(deletedTime);
        }

        @FlaggedApi(FLAG_PHR_CHANGE_LOGS)
        public DeletedLog(@NonNull String deletedRecordId, @NonNull Instant deletedTime) {
            Objects.requireNonNull(deletedRecordId);
            Objects.requireNonNull(deletedTime);
            mDeletedRecordId = deletedRecordId;
            mDeletedTime = deletedTime;
        }

        /** Returns record id of the record deleted. */
        @NonNull
        public String getDeletedRecordId() {
            return mDeletedRecordId;
        }

        /** Returns timestamp when the record was deleted. */
        @NonNull
        public Instant getDeletedTime() {
            return mDeletedTime;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DeletedLog that)) return false;
            return Objects.equals(mDeletedRecordId, that.mDeletedRecordId)
                    && Objects.equals(mDeletedTime, that.mDeletedTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mDeletedRecordId, mDeletedTime);
        }
    }

    /**
     * A change log holds the {@link MedicalResourceId} of a deleted medical resource. For privacy,
     * only unique identifiers of deleted medical resources are returned.
     *
     * <p>Clients holding copies of data from Health Connect should keep a copy of these unique
     * identifiers along with their contents. When receiving a {@link DeletedMedicalResource} in
     * {@link ChangeLogsResponse}, use the identifiers to delete copy of the data.
     */
    @FlaggedApi(FLAG_PHR_CHANGE_LOGS)
    public static final class DeletedMedicalResource {
        private final MedicalResourceId mDeletedMedicalResourceId;
        private final Instant mDeletedTime;

        /**
         * Creates a {@link DeletedMedicalResource}.
         *
         * @param deletedMedicalResourceId the {@link MedicalResourceId} of the deleted medical
         *     resource.
         * @param deletedTime the {@link Instant} when the medical resource was deleted.
         * @throws NullPointerException if {@code deletedMedicalResourceId} or {@code deletedTime}
         *     is null.
         */
        public DeletedMedicalResource(
                @NonNull MedicalResourceId deletedMedicalResourceId, @NonNull Instant deletedTime) {
            Objects.requireNonNull(deletedMedicalResourceId);
            Objects.requireNonNull(deletedTime);
            mDeletedMedicalResourceId = deletedMedicalResourceId;
            mDeletedTime = deletedTime;
        }

        /** Returns {@link MedicalResourceId} of the deleted resource. */
        @NonNull
        public MedicalResourceId getDeletedMedicalResourceId() {
            return mDeletedMedicalResourceId;
        }

        /** Returns data source id of the deleted resource. */
        @NonNull
        public String getDataSourceId() {
            return mDeletedMedicalResourceId.getDataSourceId();
        }

        /** Returns timestamp when the medical resource was deleted. */
        @NonNull
        public Instant getDeletedTime() {
            return mDeletedTime;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DeletedMedicalResource that)) return false;
            return Objects.equals(mDeletedMedicalResourceId, that.mDeletedMedicalResourceId)
                    && Objects.equals(mDeletedTime, that.mDeletedTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mDeletedMedicalResourceId, mDeletedTime);
        }
    }
}
