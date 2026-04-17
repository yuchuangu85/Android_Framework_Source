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

package android.health.connect.backuprestore;

import static com.android.healthfitness.flags.Flags.FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.healthfitness.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A request to update the backup status in Health Connect. Backup statuses are used to communicate
 * the status to the end user.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
public final class UpdateHealthConnectBackupStatusRequest implements Parcelable {

    /**
     * Indicates that the status of the backup in question is unknown. This can for example be the
     * case if the status has never been provided.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectBackupStatus
     * @hide
     */
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int BACKUP_STATUS_UNKNOWN = 0;

    /**
     * Indicates that a new backup started.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectBackupStatus
     * @hide
     */
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int BACKUP_STATUS_STARTED = 1;

    /**
     * Indicates that a backup was successful.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectBackupStatus
     * @hide
     */
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int BACKUP_STATUS_SUCCESS = 2;

    /**
     * Indicates an error of unknown nature or that does not match any of the explicitly supported
     * errors.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectBackupStatus
     * @hide
     */
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int BACKUP_STATUS_ERROR_UNKNOWN = 3;

    /**
     * Indicates that backups can not currently be done due to no lock screen knowledge factor being
     * available for the mandatory encryption.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectBackupStatus
     * @hide
     */
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int BACKUP_STATUS_ERROR_NO_LOCK_SCREEN = 4;

    /**
     * Indicates that the system service that executes backups is not installed.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectBackupStatus
     * @hide
     */
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int BACKUP_STATUS_ERROR_MISSING_COMPONENT = 5;

    /**
     * Indicates that a backup failed due to a lack of connectivity.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectBackupStatus
     * @hide
     */
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int BACKUP_STATUS_ERROR_NO_CONNECTIVITY = 6;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"BACKUP_STATUS_"},
            value = {
                BACKUP_STATUS_UNKNOWN,
                BACKUP_STATUS_STARTED,
                BACKUP_STATUS_SUCCESS,
                BACKUP_STATUS_ERROR_UNKNOWN,
                BACKUP_STATUS_ERROR_NO_LOCK_SCREEN,
                BACKUP_STATUS_ERROR_MISSING_COMPONENT,
                BACKUP_STATUS_ERROR_NO_CONNECTIVITY
            })
    public @interface BackupStatus {}

    private final int mStatusCode;
    @Nullable private final String mStatusTitle;
    @Nullable private final String mStatusMessage;
    private final long mTimestampInEpochMillis;

    private UpdateHealthConnectBackupStatusRequest(
            @BackupStatus int statusCode,
            @Nullable String statusTitle,
            @Nullable String statusMessage,
            long timestampInEpochMillis) {
        mStatusCode = statusCode;
        mStatusTitle = statusTitle;
        mStatusMessage = statusMessage;
        mTimestampInEpochMillis = timestampInEpochMillis;
    }

    /** Builder for {@link UpdateHealthConnectBackupStatusRequest} objects. */
    public static final class Builder {
        private final int mStatusCode;
        private final long mTimestampInEpochMillis;
        private @Nullable String mStatusTitle;
        private @Nullable String mStatusMessage;

        /**
         * @param statusCode The status code of the backup, indicating the specific status or error.
         *     Chose the status code from the list of supported Backup statuses. Use
         *     BACKUP_STATUS_ERROR_UNKNOWN if a backup failed for reasons that are not covered by a
         *     specific status code.
         * @param timestampInEpochMillis The timing when the status change occurred.
         */
        public Builder(@BackupStatus int statusCode, long timestampInEpochMillis) {
            mStatusCode = statusCode;
            mTimestampInEpochMillis = timestampInEpochMillis;
        }

        /**
         * Sets the optional status title that can be shown to the user to indicate the status. This
         * can be used to override system values or to provide a title when the system has none
         * available. The title needs to be translated to the system language before being passed as
         * an argument.
         */
        public Builder setStatusTitle(@Nullable String statusTitle) {
            mStatusTitle = statusTitle;
            return this;
        }

        /**
         * Sets the optional status message that can be shown to the user to further explain the
         * current status. This can be used to override system values or to provide a message when
         * the system has none available. The message needs to be translated to the system language
         * before being passed as an argument.
         */
        public Builder setStatusMessage(@Nullable String statusMessage) {
            mStatusMessage = statusMessage;
            return this;
        }

        /** Builds the {@link UpdateHealthConnectBackupStatusRequest}. */
        public UpdateHealthConnectBackupStatusRequest build() {
            return new UpdateHealthConnectBackupStatusRequest(
                    mStatusCode, mStatusTitle, mStatusMessage, mTimestampInEpochMillis);
        }
    }

    private UpdateHealthConnectBackupStatusRequest(Parcel in) {
        mStatusCode = in.readInt();
        mStatusTitle = in.readString();
        mStatusMessage = in.readString();
        mTimestampInEpochMillis = in.readLong();
    }

    public static final @NonNull Creator<UpdateHealthConnectBackupStatusRequest> CREATOR =
            new Creator<UpdateHealthConnectBackupStatusRequest>() {
                @Override
                public UpdateHealthConnectBackupStatusRequest createFromParcel(Parcel in) {
                    return new UpdateHealthConnectBackupStatusRequest(in);
                }

                @Override
                public UpdateHealthConnectBackupStatusRequest[] newArray(int size) {
                    return new UpdateHealthConnectBackupStatusRequest[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mStatusCode);
        dest.writeString(mStatusTitle);
        dest.writeString(mStatusMessage);
        dest.writeLong(mTimestampInEpochMillis);
    }

    /** Returns the status code of the backup. */
    public @BackupStatus int getStatusCode() {
        return mStatusCode;
    }

    /** Returns the optional status title. */
    @Nullable
    public String getStatusTitle() {
        return mStatusTitle;
    }

    /** Returns the optional status message. */
    @Nullable
    public String getStatusMessage() {
        return mStatusMessage;
    }

    /** Returns the timestamp of the status change. */
    public long getTimestampInEpochMillis() {
        return mTimestampInEpochMillis;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UpdateHealthConnectBackupStatusRequest)) return false;
        UpdateHealthConnectBackupStatusRequest that = (UpdateHealthConnectBackupStatusRequest) o;
        return mStatusCode == that.mStatusCode
                && mTimestampInEpochMillis == that.mTimestampInEpochMillis
                && Objects.equals(mStatusTitle, that.mStatusTitle)
                && Objects.equals(mStatusMessage, that.mStatusMessage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mStatusCode, mStatusTitle, mStatusMessage, mTimestampInEpochMillis);
    }
}
