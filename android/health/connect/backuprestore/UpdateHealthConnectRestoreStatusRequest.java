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
 * A request to update the restore status in Health Connect. Backup statuses are used to communicate
 * the status to the end user.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
public final class UpdateHealthConnectRestoreStatusRequest implements Parcelable {

    /**
     * Indicates that the status of the restore in question is unknown. This can for example be the
     * case if the status has never been provided.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectRestoreStatus
     * @hide
     */
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int RESTORE_STATUS_UNKNOWN = 0;

    /**
     * Indicates that a new restore started.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectRestoreStatus
     * @hide
     */
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int RESTORE_STATUS_STARTED = 1;

    /**
     * Indicates that a restore was successful.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectRestoreStatus
     * @hide
     */
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int RESTORE_STATUS_SUCCESS = 2;

    /**
     * Indicates an error of unknown nature or that does not match any of the explicitly supported
     * errors.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectRestoreStatus
     * @hide
     */
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int RESTORE_STATUS_ERROR_UNKNOWN = 3;

    /**
     * Indicates that restores can not currently be done due to no lock screen knowledge factor
     * being available for the mandatory encryption.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectRestoreStatus
     * @hide
     */
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int RESTORE_STATUS_ERROR_NO_LOCK_SCREEN = 4;

    /**
     * Indicates that the system service that executes restores is not installed.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectRestoreStatus
     * @hide
     */
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int RESTORE_STATUS_ERROR_MISSING_COMPONENT = 5;

    /**
     * Indicates that a restore failed due to a lack of connectivity.
     *
     * @see android.health.connect.HealthConnectManager#updateHealthConnectRestoreStatus
     * @hide
     */
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    public static final int RESTORE_STATUS_ERROR_NO_CONNECTIVITY = 6;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE_INTENT_API)
    @IntDef(
            prefix = {"RESTORE_STATUS_"},
            value = {
                RESTORE_STATUS_UNKNOWN,
                RESTORE_STATUS_STARTED,
                RESTORE_STATUS_SUCCESS,
                RESTORE_STATUS_ERROR_UNKNOWN,
                RESTORE_STATUS_ERROR_NO_LOCK_SCREEN,
                RESTORE_STATUS_ERROR_MISSING_COMPONENT,
                RESTORE_STATUS_ERROR_NO_CONNECTIVITY
            })
    public @interface RestoreStatus {}

    private final int mStatusCode;
    @Nullable private final String mStatusTitle;
    @Nullable private final String mStatusMessage;
    private final long mTimestampInEpochMillis;

    private UpdateHealthConnectRestoreStatusRequest(
            @RestoreStatus int statusCode,
            @Nullable String statusTitle,
            @Nullable String statusMessage,
            long timestampInEpochMillis) {
        mStatusCode = statusCode;
        mStatusTitle = statusTitle;
        mStatusMessage = statusMessage;
        mTimestampInEpochMillis = timestampInEpochMillis;
    }

    /** Builder for {@link UpdateHealthConnectRestoreStatusRequest} objects. */
    public static final class Builder {
        private final int mStatusCode;
        private final long mTimestampInEpochMillis;
        @Nullable private String mStatusTitle;
        @Nullable private String mStatusMessage;

        /**
         * @param statusCode The status code of the restore, indicating the specific status or
         *     error. Chose the status code from the list of supported Restore statuses. Use
         *     RESTORE_STATUS_ERROR_UNKNOWN if a restore failed for reasons that are not covered by
         *     a specific status code.
         * @param timestampInEpochMillis The timing when the status change occurred.
         */
        public Builder(@RestoreStatus int statusCode, long timestampInEpochMillis) {
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

        /** Builds the {@link UpdateHealthConnectRestoreStatusRequest}. */
        public UpdateHealthConnectRestoreStatusRequest build() {
            return new UpdateHealthConnectRestoreStatusRequest(
                    mStatusCode, mStatusTitle, mStatusMessage, mTimestampInEpochMillis);
        }
    }

    private UpdateHealthConnectRestoreStatusRequest(Parcel in) {
        mStatusCode = in.readInt();
        mStatusTitle = in.readString();
        mStatusMessage = in.readString();
        mTimestampInEpochMillis = in.readLong();
    }

    public static final @NonNull Creator<UpdateHealthConnectRestoreStatusRequest> CREATOR =
            new Creator<UpdateHealthConnectRestoreStatusRequest>() {
                @Override
                public UpdateHealthConnectRestoreStatusRequest createFromParcel(Parcel in) {
                    return new UpdateHealthConnectRestoreStatusRequest(in);
                }

                @Override
                public UpdateHealthConnectRestoreStatusRequest[] newArray(int size) {
                    return new UpdateHealthConnectRestoreStatusRequest[size];
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

    /** Returns the status code of the restore. */
    public @RestoreStatus int getStatusCode() {
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
        if (!(o instanceof UpdateHealthConnectRestoreStatusRequest)) return false;
        UpdateHealthConnectRestoreStatusRequest that = (UpdateHealthConnectRestoreStatusRequest) o;
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
