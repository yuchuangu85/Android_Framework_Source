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

package android.health.connect.backuprestore;

import static com.android.healthfitness.flags.Flags.FLAG_CLOUD_BACKUP_AND_RESTORE;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;
import java.util.Objects;

/**
 * Response for a {@link android.health.connect.HealthConnectManager#getChangesForBackup} call,
 * which contains a paginated list of data to be backed up.
 *
 * @hide
 */
@FlaggedApi(FLAG_CLOUD_BACKUP_AND_RESTORE)
@SystemApi
public final class GetChangesForBackupResponse implements Parcelable {

    // The version that the data is serialized with.
    private final int mCurrentVersion;

    @NonNull private final List<BackupChange> mChanges;

    // The changeToken to be used for the next call to resume the backup.
    @NonNull private final String mNextChangeToken;

    /**
     * @param currentVersion The version of the data contained in the response, with which the data
     *     is serialized.
     * @param changes The changes to be backed up.
     * @param nextChangeToken The change token to be used for the next call to resume the backup.
     */
    public GetChangesForBackupResponse(
            int currentVersion,
            @NonNull List<BackupChange> changes,
            @NonNull String nextChangeToken) {
        mCurrentVersion = currentVersion;
        mChanges = changes;
        mNextChangeToken = nextChangeToken;
    }

    private GetChangesForBackupResponse(Parcel in) {
        mCurrentVersion = in.readInt();
        mChanges = in.createTypedArrayList(BackupChange.CREATOR);
        mNextChangeToken = in.readString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GetChangesForBackupResponse that)) return false;
        return mCurrentVersion == that.mCurrentVersion
                && mChanges.equals(that.mChanges)
                && mNextChangeToken.equals(that.mNextChangeToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mCurrentVersion, mChanges, mNextChangeToken);
    }

    @NonNull
    public static final Creator<GetChangesForBackupResponse> CREATOR =
            new Creator<>() {
                @Override
                public GetChangesForBackupResponse createFromParcel(Parcel in) {
                    return new GetChangesForBackupResponse(in);
                }

                @Override
                public GetChangesForBackupResponse[] newArray(int size) {
                    return new GetChangesForBackupResponse[size];
                }
            };

    /**
     * The returned value should be passed to the {@link
     * android.health.connect.HealthConnectManager#canRestore} call during restore.
     *
     * @return the version of the data, with which the data is serialized.
     */
    public int getCurrentVersion() {
        return mCurrentVersion;
    }

    /**
     * @return the changes to be backed up.
     */
    @NonNull
    public List<BackupChange> getChanges() {
        return mChanges;
    }

    /**
     * Represents the pagination token to retrieve the next page of results.
     *
     * @return the change token to be used for the next call to resume the backup.
     */
    @NonNull
    public String getNextChangeToken() {
        return mNextChangeToken;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mCurrentVersion);
        dest.writeTypedList(mChanges);
        dest.writeString(mNextChangeToken);
    }
}
